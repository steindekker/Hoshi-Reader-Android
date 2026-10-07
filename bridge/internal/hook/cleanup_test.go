package hook

import "testing"

func TestClean(t *testing.T) {
	cases := []struct{ in, want string }{
		{"#FF00AA;こんにちは", "こんにちは"},
		{"%p12;今日は%Nいい天気", "今日は\nいい天気"},
		{"ここんんににちちはは", "こんにちは"},
		{"おはようおはよう", "おはよう"},
		{"おはよう\nおはよう", "おはよう"},
		{"ああ", "ああ"},         // a real word, not a doubled sentence
		{"ーーーーーー", "ーーーーーー"}, // one distinct rune, left alone
		{"line\u200b", "line"},
	}
	for _, c := range cases {
		if got := Clean(c.in); got != c.want {
			t.Errorf("Clean(%q) = %q, want %q", c.in, got, c.want)
		}
	}
}
