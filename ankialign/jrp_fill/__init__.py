"""Offline JRP sentence-markup generator (run with Anki closed).

Pure transforms: unit, align, emit, splice (stdlib only).
Heavy deps:      accent (shipped index), tokenize (Sudachi), marine_tail.
Collection I/O:  engine, cli (the `anki` library).
"""
