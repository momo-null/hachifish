#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""诊断：u2 与 a11y 节点集合差集（定位对拍一致率缺口）。"""
import sys, time
sys.path.insert(0, "harness/parity")
import m1_parity as P
import uiautomator2 as u2

P.wait_bound()
P.bridge("launch_app", {"package": "org.fossify.notes"})
time.sleep(2)
n_a11y = P.semantic_a11y(P.bridge("observe")["result"]["tree"])
d = u2.connect(P.SERIAL)
n_u2 = P.semantic_u2(d.dump_hierarchy())

kb = lambda x: (x["bounds"], x["text"], x["rid"])
su, sa = set(map(kb, n_u2)), set(map(kb, n_a11y))
print("== u2 only (max 25) ==")
for t in sorted(su - sa)[:25]:
    print("  ", t)
print("== a11y only (max 5) ==")
for t in sorted(sa - su)[:5]:
    print("  ", t)
print("== a11y all semantic nodes ==")
for t in sorted(sa):
    print("  ", t)
