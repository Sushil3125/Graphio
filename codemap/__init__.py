"""codemap — live, left-to-right code flow explorer for Python projects."""

__version__ = "0.1.0"

__all__ = ["build_graph", "iter_python_files", "__version__"]


def __getattr__(name):  # lazy, so `python -m codemap.analyzer` doesn't double-import
    if name in ("build_graph", "iter_python_files"):
        from . import analyzer

        return getattr(analyzer, name)
    raise AttributeError(f"module 'codemap' has no attribute {name!r}")
