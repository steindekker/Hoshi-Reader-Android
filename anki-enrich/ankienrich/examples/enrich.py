"""Example-sentence lookup for the pack: the rendered 例文 block for one word.
The note writing lives in ankienrich.fill."""
from ankienrich.examples import index, render


def _compute(conn, term, reading) -> str:
    return render.render_block(index.select(conn, term, reading))


def examples_for(conn, term, reading) -> str:
    """Public single-note examples block (HTML), '' when none."""
    return _compute(conn, term, reading)
