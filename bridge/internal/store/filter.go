package store

import (
	"fmt"
	"regexp"
	"strings"
	"unicode"
	"unicode/utf8"
)

// FilterOptions configures how raw hooked text is cleaned before it is stored.
type FilterOptions struct {
	// Drop discards a line when any pattern matches the cleaned text.
	Drop []string
	// Strip removes every match of these patterns before trimming.
	Strip []string
	// JoinLines removes line breaks inside a line (VN text boxes wrap
	// sentences visually; the break is not part of the sentence).
	JoinLines bool
	// MaxLength drops lines longer than this many characters (0 = no limit).
	MaxLength int
}

// Normalizer applies FilterOptions. The zero value only trims.
type Normalizer struct {
	drop      []*regexp.Regexp
	strip     []*regexp.Regexp
	joinLines bool
	maxLength int
}

// NewNormalizer compiles the filter patterns.
func NewNormalizer(o FilterOptions) (*Normalizer, error) {
	n := &Normalizer{joinLines: o.JoinLines, maxLength: o.MaxLength}
	for _, p := range o.Drop {
		re, err := regexp.Compile(p)
		if err != nil {
			return nil, fmt.Errorf("drop pattern %q: %w", p, err)
		}
		n.drop = append(n.drop, re)
	}
	for _, p := range o.Strip {
		re, err := regexp.Compile(p)
		if err != nil {
			return nil, fmt.Errorf("strip pattern %q: %w", p, err)
		}
		n.strip = append(n.strip, re)
	}
	return n, nil
}

// Normalize returns the cleaned line and whether it should be kept.
func (n *Normalizer) Normalize(raw string) (string, bool) {
	s := strings.ToValidUTF8(raw, "")
	s = strings.ReplaceAll(s, "\x00", "")
	if n != nil {
		for _, re := range n.strip {
			s = re.ReplaceAllString(s, "")
		}
		if n.joinLines {
			s = joinLines(s)
		}
	}
	s = strings.TrimSpace(s)
	if s == "" {
		return "", false
	}
	if n != nil {
		if n.maxLength > 0 && utf8.RuneCountInString(s) > n.maxLength {
			return "", false
		}
		for _, re := range n.drop {
			if re.MatchString(s) {
				return "", false
			}
		}
	}
	return s, true
}

// joinLines removes line breaks and the whitespace around them. A single
// space is kept only between two Latin-script words so that English text
// does not get glued together; Japanese text is joined without a space.
func joinLines(s string) string {
	if !strings.ContainsAny(s, "\r\n") {
		return s
	}
	parts := strings.FieldsFunc(s, func(r rune) bool { return r == '\n' || r == '\r' })
	var b strings.Builder
	for _, p := range parts {
		p = strings.TrimSpace(p)
		if p == "" {
			continue
		}
		if b.Len() > 0 {
			last, _ := utf8.DecodeLastRuneInString(b.String())
			first, _ := utf8.DecodeRuneInString(p)
			if isLatin(last) && isLatin(first) {
				b.WriteByte(' ')
			}
		}
		b.WriteString(p)
	}
	return b.String()
}

func isLatin(r rune) bool {
	return r < 0x250 && (unicode.IsLetter(r) || unicode.IsDigit(r) || unicode.IsPunct(r))
}
