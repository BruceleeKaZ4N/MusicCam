#!/usr/bin/env python3
"""Inspect a local PoC WAV without dependencies; prints metrics, never sample contents."""
import argparse
import array
import json
import math
from pathlib import Path
import struct
import sys
import wave


def inspect(path):
    size = path.stat().st_size
    with path.open("rb") as stream:
        header = stream.read(12)
        if len(header) != 12 or header[:4] != b"RIFF" or header[8:] != b"WAVE":
            raise ValueError("not RIFF/WAVE")
        if struct.unpack_from("<I", header, 4)[0] + 8 != size:
            raise ValueError("RIFF size does not match actual file size")
    with wave.open(str(path), "rb") as recording:
        channels, width, rate, frames, compression, _ = recording.getparams()
        if (channels, width, rate, compression) != (2, 2, 48000, "NONE"):
            raise ValueError(f"unexpected format: {recording.getparams()}")
        if frames == 0:
            raise ValueError("no PCM frames")
        peaks = [0] * channels
        nonzero = [0] * channels
        squares = [0] * channels
        read_frames = 0
        while block := recording.readframes(48000):
            if len(block) % (channels * width):
                raise ValueError("incomplete PCM frame")
            samples = array.array("h", block)
            if sys.byteorder != "little":
                samples.byteswap()
            read_frames += len(samples) // channels
            for channel in range(channels):
                values = samples[channel::channels]
                peaks[channel] = max(peaks[channel], max(abs(value) for value in values))
                nonzero[channel] += sum(value != 0 for value in values)
                squares[channel] += sum(value * value for value in values)
        if read_frames != frames:
            raise ValueError(f"truncated data: header={frames}, actual={read_frames}")
    return {
        "file": path.name,
        "file_bytes": size,
        "pcm_bytes": frames * channels * width,
        "sample_rate": rate,
        "channels": channels,
        "bits_per_sample": width * 8,
        "frames": frames,
        "duration_seconds": frames / rate,
        "nonzero_samples_per_channel": nonzero,
        "peak_per_channel": peaks,
        "rms_per_channel": [math.sqrt(total / frames) for total in squares],
        "all_silent": not any(nonzero),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("files", nargs="+", type=Path)
    parser.add_argument("--expect", choices=["non-silent", "silent"])
    args = parser.parse_args()
    failed = False
    for path in args.files:
        try:
            result = inspect(path)
            if args.expect:
                result["expectation_met"] = result["all_silent"] == (args.expect == "silent")
                failed |= not result["expectation_met"]
            print(json.dumps(result, ensure_ascii=False, indent=2))
        except (OSError, ValueError, wave.Error, EOFError) as error:
            failed = True
            print(json.dumps({"file": path.name, "error": str(error)}, ensure_ascii=False))
    return int(failed)


if __name__ == "__main__":
    raise SystemExit(main())
