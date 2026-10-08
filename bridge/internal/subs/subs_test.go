package subs

import (
	"testing"

	"golang.org/x/text/encoding/japanese"
)

func TestParseSRT(t *testing.T) {
	src := "\ufeff1\r\n00:00:01,000 --> 00:00:02,500\r\n<i>こんにちは</i>\r\n  世界  \r\n\r\n2\r\n00:01:02,03 --> 00:01:04,000\r\n{\\an8}次の行\r\n\r\n3\r\n00:01:05,000 --> 00:01:06,000\r\n\r\n"
	cues, err := Parse(SRT, Decode([]byte(src)))
	if err != nil {
		t.Fatal(err)
	}
	if len(cues) != 2 {
		t.Fatalf("got %+v", cues)
	}
	if cues[0] != (Cue{1000, 2500, "こんにちは\n世界"}) {
		t.Fatalf("cue 0 %+v", cues[0])
	}
	if cues[1] != (Cue{62030, 64000, "次の行"}) {
		t.Fatalf("cue 1 %+v", cues[1])
	}
}

func TestParseVTT(t *testing.T) {
	src := "WEBVTT\n\nNOTE comment\n\nintro\n00:01.000 --> 00:03.000 align:start\n<v Taro>やあ</v> <00:02.000><c>ね</c>\n\n01:00:00.000 --> 01:00:01.000\n&lt;終&gt;\n"
	cues, err := Parse(VTT, src)
	if err != nil {
		t.Fatal(err)
	}
	if len(cues) != 2 || cues[0] != (Cue{1000, 3000, "やあ ね"}) || cues[1] != (Cue{3600000, 3601000, "<終>"}) {
		t.Fatalf("got %+v", cues)
	}
}

func TestParseASS(t *testing.T) {
	src := `[Script Info]
Title: test

[V4+ Styles]
Format: Name, Fontname
Style: Default,Arial

[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
Dialogue: 0,0:00:05.00,0:00:07.50,Default,,0,0,0,,{\i1}行くぞ{\i0}\N早く, 急げ
Dialogue: 0,0:00:01.00,0:00:02.00,Sign,,0,0,0,,{\pos(10,10)}看板
Dialogue: 1,0:00:01.00,0:00:02.00,Sign,,0,0,0,,{\pos(10,10)}看板
Dialogue: 0,0:00:03.00,0:00:04.00,Default,,0,0,0,,{\p1}m 0 0 l 100 0 100 100{\p0}
Comment: 0,0:00:03.00,0:00:04.00,Default,,0,0,0,,コメント
`
	cues, err := Parse(ASS, src)
	if err != nil {
		t.Fatal(err)
	}
	// Signs are kept (filtering is out of scope); exact duplicates and drawings are not dialogue.
	if len(cues) != 2 || cues[0] != (Cue{1000, 2000, "看板"}) || cues[1] != (Cue{5000, 7500, "行くぞ\n早く, 急げ"}) {
		t.Fatalf("got %+v", cues)
	}
}

func TestDecodeShiftJIS(t *testing.T) {
	sjis, _ := japanese.ShiftJIS.NewEncoder().Bytes([]byte("1\n00:00:00,000 --> 00:00:01,000\n日本語\n"))
	cues, _ := Parse(SRT, Decode(sjis))
	if len(cues) != 1 || cues[0].Text != "日本語" {
		t.Fatalf("got %+v", cues)
	}
	utf16 := []byte{0xFF, 0xFE, 'a', 0, 0x42, 0x30}
	if got := Decode(utf16); got != "aあ" {
		t.Fatalf("utf16 %q", got)
	}
}

func TestFormatFor(t *testing.T) {
	for _, c := range []struct {
		codec, path string
		want        Format
		ok          bool
	}{
		{"subrip", "", SRT, true}, {"", "/a/b.ASS", ASS, true}, {"webvtt", "x.mkv", VTT, true},
		{"hdmv_pgs_subtitle", "x.sup", "", false},
	} {
		if f, ok := FormatFor(c.codec, c.path); f != c.want || ok != c.ok {
			t.Errorf("%v: %v %v", c, f, ok)
		}
	}
}
