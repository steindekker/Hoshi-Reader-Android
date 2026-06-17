import os

import pytest

from ankienrich import images

# A real Bing `a.iusc` `m`-attribute payload (captured live, 紫陽花 search).
REAL_M = (
    '{"sid":"","cturl":"","cid":"o72MmeQM",'
    '"purl":"https://allabout-japan.com/zh-tw/article/5466/",'
    '"murl":"https://imgcp.aacdn.jp/img-a/1720/auto/x.jpg",'
    '"turl":"https://ts1.mm.bing.net/th?id=OIP.o72MmeQMDCc&pid=15.1",'
    '"md5":"a3bd8c99","shkey":"t/rtSYp","t":"紫陽花 | All About Japan",'
    '"mid":"5F8A644C","desc":"desc text"}'
)


def test_safesearch_is_off():
    # SafeSearch disabled via both the URL param and the cookie Bing honours.
    assert "adlt=off" in images.SEARCH_URL
    assert images._SAFESEARCH_COOKIE["value"] == "ADLT=OFF"
    assert images._SAFESEARCH_COOKIE["domain"] == ".bing.com"


def test_parse_iusc_maps_one_payload():
    [c] = images.parse_iusc([REAL_M])
    assert c.thumb_url == "https://ts1.mm.bing.net/th?id=OIP.o72MmeQMDCc&pid=15.1"
    assert c.full_url == "https://imgcp.aacdn.jp/img-a/1720/auto/x.jpg"
    assert c.title == "紫陽花 | All About Japan"
    assert c.source_page == "https://allabout-japan.com/zh-tw/article/5466/"


def test_parse_iusc_skips_malformed_json():
    # A broken `m` blob must not sink the whole result set.
    out = images.parse_iusc(["{not json", REAL_M])
    assert len(out) == 1
    assert out[0].title == "紫陽花 | All About Japan"


def test_parse_iusc_skips_entries_without_image_urls():
    # Placeholder/ad nodes carry empty murl/turl — unusable, drop them.
    blank = '{"turl":"","murl":"","t":"ad","purl":"https://x"}'
    no_thumb = '{"turl":"","murl":"https://x/full.jpg","t":"a","purl":"https://x"}'
    out = images.parse_iusc([blank, no_thumb, REAL_M])
    assert len(out) == 1
    assert out[0].full_url == "https://imgcp.aacdn.jp/img-a/1720/auto/x.jpg"


def test_parse_iusc_dedupes_by_full_url_preserving_order():
    a = '{"turl":"https://t/1","murl":"https://x/a.jpg","t":"A"}'
    b = '{"turl":"https://t/2","murl":"https://x/b.jpg","t":"B"}'
    a_dup = '{"turl":"https://t/3","murl":"https://x/a.jpg","t":"A again"}'
    out = images.parse_iusc([a, b, a_dup])
    assert [c.full_url for c in out] == ["https://x/a.jpg", "https://x/b.jpg"]


# --- live Bing integration: opt-in (network + chromium), skipped by default ---

LIVE = os.environ.get("ANKIENRICH_LIVE_IMAGE_TEST") == "1"


@pytest.mark.skipif(not LIVE, reason="set ANKIENRICH_LIVE_IMAGE_TEST=1 to hit Bing")
def test_search_images_live_returns_candidates():
    term = "林檎"
    out = images.search_images(term, limit=5)
    assert 1 <= len(out) <= 5
    c = out[0]
    assert c.thumb_url.startswith("http") and c.full_url.startswith("http")
    # turl is always a Bing CDN thumbnail
    assert "bing.net" in c.thumb_url
    # Relevance guard: the real results mention the term; the bot-detection
    # default feed (wrong User-Agent) does not. Catches a silent junk feed.
    assert any(term in c.title for c in out)
