import pytest

from ankienrich import runlock


def test_second_acquire_is_busy():
    with runlock.acquire():
        with pytest.raises(runlock.Busy):
            with runlock.acquire():
                pass


def test_lock_is_reusable_after_release():
    with runlock.acquire():
        pass
    with runlock.acquire():  # must not raise — first was released
        pass
