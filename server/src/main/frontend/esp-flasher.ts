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
  stage(text: string): void;
  progress(percent: number): void;
  flashed(): void;
  failed(message: string): void;
}

type FlasherElement = HTMLElement & { $server: FlasherServer };

/** The chip the firmware is built for; anything else gets a wrong image. */
const EXPECTED_CHIP = "ESP32-S3";

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

async function flashEsp32(element: FlasherElement, image: HTMLAnchorElement): Promise<void> {
  const server = element.$server;
  const serial = (navigator as any).serial;
  if (!serial) {
    server.failed("This browser cannot open serial ports. Use Chrome or Edge on a computer.");
    return;
  }

  let transport: Transport | undefined;
  try {
    const port = await serial.requestPort({});
    server.stage("Connecting to the board");
    transport = new Transport(port, false);
    const loader = new ESPLoader({ transport, baudrate: BAUDRATE, terminal: quietTerminal });

    const chip = await loader.main();
    if (!chip.includes(EXPECTED_CHIP)) {
      throw new Error(
        `This board is a ${chip}, and the firmware was built for an ${EXPECTED_CHIP}. ` +
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
    server.flashed();
  } catch (error) {
    server.failed(describe(error));
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
    ScrewCloud?: { flashEsp32?: typeof flashEsp32 };
  }
}

window.ScrewCloud = { ...(window.ScrewCloud ?? {}), flashEsp32 };
