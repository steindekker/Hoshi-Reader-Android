from ankienrich import massif

_FIXTURE = '''
<div id="results-list"><ul>
<li class="text-japanese">
  <div>いろいろと<em>勉強</em>になりました。</div>
  <div class="result-meta"><a class="source_link" href="x">src</a></div>
</li>
<li class="text-japanese">
  <div><em>勉強</em>させて&#12356;ただき、ありがとうございました</div>
  <div class="result-meta"><a class="source_link" href="y">src</a></div>
</li>
<li class="text-japanese">
  <div>とっても<em>勉強</em>になりました。</div>
</li>
</ul></div>
'''

def test_parse_extracts_sentences_strips_em_and_unescapes():
    out = massif.parse(_FIXTURE, n=2)
    assert out == ["いろいろと勉強になりました。",
                   "勉強させていただき、ありがとうございました"]

def test_parse_caps_at_n():
    assert len(massif.parse(_FIXTURE, n=1)) == 1

def test_top_sentences_returns_empty_on_network_error(monkeypatch):
    def _boom(*a, **k):
        raise massif.requests.RequestException("down")
    monkeypatch.setattr(massif.requests, "get", _boom)
    assert massif.top_sentences("勉強", n=5, timeout=1) == []

def test_parse_empty_returns_empty():
    assert massif.parse("", n=5) == []

def test_parse_tolerates_extra_li_attributes():
    body = ('<li class="text-japanese" data-id="9">'
            '<div>これは<em>例</em>です。</div></li>')
    assert massif.parse(body, n=5) == ["これは例です。"]
