#!/usr/bin/env python3
"""Sends a test measurement packet to the server without a real device.

    ./send-test-packet.py                       # localhost:5555, default data
    ./send-test-packet.py --host 10.0.0.5 --device TALO
    ./send-test-packet.py --sensor ULK=-3.2,88.1
"""

import argparse
import socket
import struct

VERSION = 1
TEMPERATURE_INVALID = -0x8000
HUMIDITY_INVALID = 0xFFFF


def encode_id(value):
    return value.encode("ascii")[:4].ljust(4, b" ")


def encode_sensor(sensor_id, temperature, humidity):
    raw_temperature = TEMPERATURE_INVALID if temperature is None else round(temperature * 100)
    raw_humidity = HUMIDITY_INVALID if humidity is None else round(humidity * 100)
    return encode_id(sensor_id) + struct.pack(">hH", raw_temperature, raw_humidity)


def build_packet(device_id, sequence, sensors):
    header = struct.pack(">B", VERSION) + encode_id(device_id) + struct.pack(">BH", len(sensors), sequence)
    return header + b"".join(encode_sensor(*sensor) for sensor in sensors)


def parse_sensor(text):
    """ULK=-3.2,88.1 or ULK=-3.2, (humidity omitted)"""
    sensor_id, _, values = text.partition("=")
    temperature, _, humidity = values.partition(",")
    return (
        sensor_id,
        float(temperature) if temperature else None,
        float(humidity) if humidity else None,
    )


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=5555)
    parser.add_argument("--device", default="TEST")
    parser.add_argument("--sequence", type=int, default=1)
    parser.add_argument("--sensor", action="append", type=parse_sensor,
                        help="ID=temperature,humidity — may be repeated")
    args = parser.parse_args()

    sensors = args.sensor or [
        ("DHT", 25.6, 35.7),
        ("RBF", 24.93, 40.77),
        ("ULK", -3.2, 88.1),
    ]

    packet = build_packet(args.device, args.sequence, sensors)
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        sock.sendto(packet, (args.host, args.port))

    print(f"Sent {len(packet)} bytes to {args.host}:{args.port}")
    print(f"  device {args.device}, sequence {args.sequence}, {len(sensors)} sensors")
    print(f"  {packet.hex(' ')}")


if __name__ == "__main__":
    main()
