/*
  Writes an ESP32 image over a serial port the reader picks, for SerialFlasher.

  esptool-js is Espressif's own flasher for browsers, and this is a thin wrapper
  around its three calls: connect, write, reset. What the wrapper adds is the
  conversation with the server-side component — where the flasher has got to,
  how far the write is, and whether it ended well — through the $server methods
  Vaadin exposes on the element, so that every word the reader sees is chosen on
  the Java side next to the rest of the page.

  The image is fetched from the page's own hidden link. That keeps the bytes on
  the same session-bound path as a download, and keeps this script ignorant of
  where the server stores anything.
*/

import { ESPLoader, Transport } from "esptool-js";

/** The @ClientCallable methods of SerialFlasher. */
interface FlasherServer {
  started(): void;
  stage(text: string): void;
  progress(percent: number): void;
  flashed(): void;
  failed(message: string, detail: string): void;
}

const LOG = "[ScrewCloud flasher]";

/** What Transport takes: the browser's SerialPort, named through esptool-js. */
type Port = ConstructorParameters<typeof Transport>[0];

type FlasherElement = HTMLElement & { $server: FlasherServer };

/** Espressif's stub flasher talks at this after the initial handshake. */
const BAUDRATE = 921600;

/*
  esptool-js narrates into a terminal. There is none here — the reader gets the
  stage messages instead — so it talks to a terminal that keeps quiet.
*/
const quietTerminal = {
  clean(): void {},
  writeLine(_line: string): void {},
  write(_text: string): void {},
};

/*
  Attaches the flasher to the button. The port has to be asked for inside the
  click's own handler — the browser only shows its port dialog while it is
  handling a user gesture, and a click that has been to the server and back
  is not one any more. So the button's click never reaches the server: the
  listener below asks for the port first, synchronously, and tells the server
  afterwards.
*/
function armFlasher(
  element: FlasherElement,
  button: HTMLElement,
  image: HTMLAnchorElement,
  expectedChip: string,
): boolean {
  if ((button as any).__screwcloudArmed) {
    return true;
  }
  (button as any).__screwcloudArmed = true;
  button.addEventListener("click", (event) => {
    event.preventDefault();
    const serial = (navigator as any).serial;
    if (!serial) {
      element.$server.failed(
        "This browser cannot open serial ports. Use Chrome or Edge on a computer.",
        "navigator.serial is undefined",
      );
      return;
    }
    // Before any await: this is the call that needs the gesture.
    const port: Promise<Port> = serial.requestPort({});
    element.$server.started();
    console.info(LOG, "asked for a port");
    void flashEsp32(element, port, image, expectedChip);
  });
  console.info(LOG, "armed", button);
  return true;
}

/*
  expectedChip is what the bootloader must call itself — "ESP32-S3", "ESP32-C3" —
  for the image to be the right one; the server knows which board it built for.
*/
async function flashEsp32(
  element: FlasherElement,
  chosenPort: Promise<Port>,
  image: HTMLAnchorElement,
  expectedChip: string,
): Promise<void> {
  const server = element.$server;

  let transport: Transport | undefined;
  try {
    const port = await chosenPort;
    console.info(LOG, "port chosen", port.getInfo?.());
    server.stage("Connecting to the board");
    transport = new Transport(port, false);
    const loader = new ESPLoader({ transport, baudrate: BAUDRATE, terminal: quietTerminal });

    const chip = await loader.main();
    console.info(LOG, "connected to", chip);
    if (!chip.includes(expectedChip)) {
      throw new Error(
        `This board is a ${chip}, and the firmware was built for an ${expectedChip}. ` +
          "Nothing was written.",
      );
    }

    server.stage("Fetching the firmware from the server");
    const response = await fetch(image.href);
    if (!response.ok) {
      throw new Error(
        response.status === 404 || response.status === 410
          ? "The file has already been downloaded and deleted. Build again."
          : `The server would not hand over the file (${response.status}).`,
      );
    }
    const data = new Uint8Array(await response.arrayBuffer());
    console.info(LOG, "image fetched,", data.length, "bytes");

    server.stage("Writing");
    let lastReported = -1;
    await loader.writeFlash({
      fileArray: [{ data, address: 0x0 }],
      flashSize: "keep",
      flashMode: "keep",
      flashFreq: "keep",
      eraseAll: false,
      compress: true,
      reportProgress: (_fileIndex: number, written: number, total: number) => {
        /*
           Whole per-cent steps, and only when the number changes: every report
           is a round trip to the server, and the write reports far more often
           than a progress bar can show.
        */
        const percent = Math.floor((written * 100) / total);
        if (percent !== lastReported && percent % 2 === 0) {
          lastReported = percent;
          server.progress(percent);
        }
      },
    });

    server.stage("Restarting the board");
    await loader.after("hard_reset");
    console.info(LOG, "done");
    server.flashed();
  } catch (error) {
    console.error(LOG, error);
    server.failed(describe(error), String(error));
  } finally {
    if (transport) {
      try {
        await transport.disconnect();
      } catch {
        // The port may already be gone; there is nothing left to tidy.
      }
    }
  }
}

/** What went wrong, in words a reader who did not write this can act on. */
function describe(error: unknown): string {
  if (error instanceof DOMException && error.name === "NotFoundError") {
    return "No port was chosen. Press the button again and pick the board's port.";
  }
  if (error instanceof DOMException && error.name === "SecurityError") {
    return "The browser would not ask for a port: it wants the request to come straight from a click. Press the button again; if it repeats, reload the page.";
  }
  if (error instanceof DOMException && (error.name === "NetworkError" || error.name === "InvalidStateError")) {
    return "The port could not be opened. If another program — the Arduino IDE's serial monitor, say — has it open, close that and try again.";
  }
  const message = error instanceof Error ? error.message : String(error);
  if (/timeout|Timed out|Failed to connect/i.test(message)) {
    return "The board did not answer. Hold its BOOT button while plugging the cable in, then try again.";
  }
  return message;
}

declare global {
  interface Window {
    ScrewCloud?: { armFlasher?: typeof armFlasher };
  }
}

window.ScrewCloud = { ...(window.ScrewCloud ?? {}), armFlasher };
// So that an empty console means the module never arrived, not that it sat idle.
console.info(LOG, "module loaded");
