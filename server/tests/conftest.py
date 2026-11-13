"""Shared pytest configuration.

The APK-building fixtures live in `apk_factory` so they can be imported by test
modules directly; importing them here is what registers them with pytest.
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from tests.apk_factory import (  # noqa: E402,F401
    debug_keystore,
    keystore,
    sdk_available,
)
