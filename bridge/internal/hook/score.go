package hook

import (
	"math"
	"regexp"
	"strings"
	"time"
	"unicode"
	"unicode/utf8"
)

// isJapanese reports kana, kanji and Japanese/full-width punctuation.
func isJapanese(r rune) bool {
	switch {
	case r >= 0x3040 && r <= 0x30FF: // hiragana, katakana
		return true
	case r >= 0x31F0 && r <= 0x31FF: // katakana phonetic extensions
		return true
	case r >= 0x3400 && r <= 0x4DBF, r >= 0x4E00 && r <= 0x9FFF, r >= 0xF900 && r <= 0xFAFF: // kanji
		return true
	case r >= 0x3000 && r <= 0x303F: // CJK punctuation (、。「」『』…)
		return true
	case r >= 0xFF01 && r <= 0xFF9F: // full-width ASCII, half-width katakana
		return true
	case r == '…' || r == '―' || r == '‥' || r == '—' || r == '♪':
		return true
	}
	return false
}

const dialoguePunct = "「」『』（）〈〉《》。、！？…―‥"

var reTechnical = regexp.MustCompile(`(?i)(\.(dll|exe|png|jpe?g|bmp|ogg|wav|mp[34]|txt|json|xml|dat|pak|ks|scn|ttf|otf)\b|https?:|[A-Za-z]:\\|\\[A-Za-z0-9_]+\\|/[A-Za-z0-9_.-]+/)`)

// sampleQuality rates one cleaned sentence from 0 (garbage) to ~1.15
// (Japanese dialogue).
func sampleQuality(s string) float64 {
	s = strings.TrimSpace(s)
	if s == "" {
		return 0
	}
	var total, jp, ctrl int
	distinct := map[rune]bool{}
	for _, r := range s {
		if unicode.IsSpace(r) {
			continue
		}
		total++
		distinct[r] = true
		if isJapanese(r) {
			jp++
		}
		if unicode.IsControl(r) || r == utf8.RuneError {
			ctrl++
		}
	}
	if total == 0 {
		return 0
	}
	q := float64(jp) / float64(total)
	switch n := total; {
	case n < 2:
		q *= 0.05
	case n < 4:
		q *= 0.4
	case n <= 150:
	case n <= 300:
		q *= 0.7
	default:
		q *= 0.3
	}
	if strings.ContainsAny(s, dialoguePunct) {
		q *= 1.15
	}
	if total >= 4 && len(distinct) <= 2 {
		q *= 0.1 // "ーーーー", "■■■■"
	}
	if reTechnical.MatchString(s) {
		q *= 0.2
	}
	if ctrl > 0 {
		q *= 0.3
	}
	return q
}

// scoreThread combines the quality of a thread's recent samples with how
// much evidence there is, how varied the samples are, and how often the
// thread fires. Dialogue threads score about 0.8-1.2; garbage near 0.
func scoreThread(samples []Sample, times []time.Time, now time.Time) float64 {
	if len(samples) == 0 {
		return 0
	}
	recent := samples
	if len(recent) > 8 {
		recent = recent[len(recent)-8:]
	}
	var sum float64
	distinct := map[string]bool{}
	for _, s := range recent {
		sum += sampleQuality(s.Text)
		distinct[s.Text] = true
	}
	n := float64(len(recent))
	score := sum / n
	// More samples = more confidence (1 sample: 0.625, 4+: 1).
	score *= math.Min(1, 0.5+0.125*n)
	// A thread that keeps repeating the same text (a name plate, a menu
	// label) is unlikely to be the dialogue.
	if len(recent) >= 2 {
		score *= 0.4 + 0.6*float64(len(distinct))/n
	}
	// Dialogue advances when the player presses a button: a few lines per
	// minute, not several per second.
	var last30 int
	for _, t := range times {
		if now.Sub(t) <= 30*time.Second {
			last30++
		}
	}
	switch {
	case last30 > 30:
		score *= 0.2
	case last30 > 12:
		score *= 0.6
	}
	return math.Round(score*1000) / 1000
}
