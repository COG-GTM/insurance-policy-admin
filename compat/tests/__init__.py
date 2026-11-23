"""Unit tests for the compat gate scripts.

Run from the repo root: python3 -m unittest discover -s compat/tests -t compat
"""

import os
import sys

COMPAT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
for p in (COMPAT, os.path.join(COMPAT, "api")):
    if p not in sys.path:
        sys.path.insert(0, p)
