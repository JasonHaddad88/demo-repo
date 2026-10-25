"""Double-click to run VortexView from source (needs Python and Pillow).

The .pyw extension makes Windows start it without a console window.
"""

import sys
from pathlib import Path

# Make `import vortexview` work when this file is started directly.
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from vortexview.app import main  # noqa: E402

raise SystemExit(main())
