package hook

import (
	"regexp"
	"strings"
	"unicode"
	"unicode/utf8"
)

var (
	// MAGES/5pb script residue that LunaHook's engine filters occasionally
	// leave behind: colour codes "#RRGGBB;", parameterised codes "%p...;",
	// single-letter control codes "%N" (newline), "%r", "%C", "%B", ... and
	// a literal backslash-n.
	reMagesColor = regexp.MustCompile(`#[0-9A-Fa-f]{6};`)
	reMagesParam = regexp.MustCompile(`%[pP][^;%]{0,16};`)
	reMagesCode  = regexp.MustCompile(`%[A-Za-z]`)
	reLiteralNL  = regexp.MustCompile(`\\n`)
)

// Clean removes common LunaHook output artifacts from one sentence:
// control and zero-width characters, MAGES control codes, every character
// doubled ("ここんんににちちはは"), and a whole sentence repeated two or three
// times (hooks that fire once per rendering pass). It does not trim.
func Clean(s string) string {
	s = strings.ToValidUTF8(s, "")
	s = reMagesColor.ReplaceAllString(s, "")
	s = reMagesParam.ReplaceAllString(s, "")
	s = reMagesCode.ReplaceAllStringFunc(s, func(m string) string {
		if m == "%N" || m == "%n" {
			return "\n"
		}
		return ""
	})
	s = reLiteralNL.ReplaceAllString(s, "\n")
	s = strings.Map(func(r rune) rune {
		switch {
		case r == '\n' || r == '\t':
			return r
		case r == '\r':
			return '\n'
		case unicode.IsControl(r):
			return -1
		case r >= 0x200B && r <= 0x200F, r == 0xFEFF, r == 0x2060:
			return -1
		}
		return r
	}, s)
	s = undouble(s)
	s = unrepeat(s)
	return s
}

// undouble collapses text in which every character appears twice in a row.
// Short strings are left alone ("ああ" is a real word).
func undouble(s string) string {
	r := []rune(s)
	if len(r) < 6 || len(r)%2 != 0 {
		return s
	}
	distinct := map[rune]bool{}
	for i := 0; i < len(r); i += 2 {
		if r[i] != r[i+1] {
			return s
		}
		distinct[r[i]] = true
	}
	if len(distinct) < 2 {
		return s // "ーーーーーー" etc.
	}
	out := make([]rune, 0, len(r)/2)
	for i := 0; i < len(r); i += 2 {
		out = append(out, r[i])
	}
	return string(out)
}

// unrepeat turns "ABCABC" or "ABCABCABC" into "ABC" (also with a line
// break or space between the copies).
func unrepeat(s string) string {
	t := strings.TrimSpace(s)
	n := utf8.RuneCountInString(t)
	if n < 4 {
		return s
	}
	for k := 3; k >= 2; k-- {
		r := []rune(t)
		for _, sepLen := range []int{0, 1} {
			if (len(r)-(k-1)*sepLen)%k != 0 {
				continue
			}
			part := (len(r) - (k-1)*sepLen) / k
			if part < 2 {
				continue
			}
			first := string(r[:part])
			ok := true
			for i := 1; i < k && ok; i++ {
				start := i * (part + sepLen)
				if sepLen == 1 && !unicode.IsSpace(r[start-1]) {
					ok = false
					break
				}
				ok = string(r[start:start+part]) == first
			}
			if ok {
				// "ーーーー" is a stretched sound, not a sentence pasted twice.
				distinct := map[rune]bool{}
				for _, c := range r[:part] {
					distinct[c] = true
				}
				if len(distinct) < 2 {
					continue
				}
				return first
			}
		}
	}
	return s
}
