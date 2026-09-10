#!/usr/bin/env python3
"""
Convenience entry point for running Penik E2E tests.
Usage:
    python3 scripts/run_e2e.py [--url http://localhost:8143]
"""

import os
import sys

# Ensure repository root is in Python path
REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if REPO_ROOT not in sys.path:
    sys.path.insert(0, REPO_ROOT)

from tests.e2e.test_runner import main

if __name__ == "__main__":
    main()
