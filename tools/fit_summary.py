#!/usr/bin/env python
"""Разбор .fit с Cycplus M2 — что именно поедет в Health Connect.

Печатает session/lap-сводку и наличие потоков (HR, каденс, мощность, GPS),
чтобы понять, какие Health Connect Record'ы имеет смысл писать.
"""

import glob
import os
import sys
from collections import Counter

import fitdecode

ROOT = os.path.dirname(os.path.abspath(__file__))


def summarize(path):
    print(f"\n=== {os.path.basename(path)} ({os.path.getsize(path)} байт) ===")
    msg_counts = Counter()
    sessions, records = [], []
    record_fields = Counter()

    with fitdecode.FitReader(path) as fit:
        for frame in fit:
            # isinstance вместо сравнения frame_type: то же условие, но из него
            # виден тип кадра — иначе .name и .fields читаются у любого кадра.
            if not isinstance(frame, fitdecode.FitDataMessage):
                continue
            msg_counts[frame.name] += 1
            if frame.name == "session":
                sessions.append({f.name: f.value for f in frame.fields})
            elif frame.name == "record":
                d = {f.name: f.value for f in frame.fields}
                records.append(d)
                for k, v in d.items():
                    if v is not None:
                        record_fields[k] += 1

    print("Сообщения:", dict(msg_counts))

    for s in sessions:
        keys = (
            "sport",
            "sub_sport",
            "start_time",
            "total_elapsed_time",
            "total_timer_time",
            "total_distance",
            "total_calories",
            "avg_speed",
            "max_speed",
            "avg_heart_rate",
            "max_heart_rate",
            "avg_cadence",
            "avg_power",
            "total_ascent",
            "total_descent",
        )
        print("\n-- session")
        for k in keys:
            if s.get(k) is not None:
                print(f"   {k:22} {s[k]}")

    if records:
        print(f"\n-- record: {len(records)} точек")
        print(
            "   поля с данными:",
            ", ".join(f"{k}({v})" for k, v in record_fields.most_common()),
        )
        first, last = records[0], records[-1]
        print(f"   первая точка: {first.get('timestamp')}")
        print(f"   последняя:    {last.get('timestamp')}")
        has_gps = any(r.get("position_lat") is not None for r in records)
        print(f"   GPS-трек: {'есть' if has_gps else 'НЕТ'}")


if __name__ == "__main__":
    paths = sys.argv[1:] or sorted(glob.glob(os.path.join(ROOT, "fit", "*.fit")))
    if not paths:
        print("Нет .fit файлов — сначала запусти m2_sync.py")
        sys.exit(1)
    for p in paths:
        summarize(p)
