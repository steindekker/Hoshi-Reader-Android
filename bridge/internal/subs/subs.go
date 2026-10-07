// Package subs parses text subtitle files (SRT, WebVTT, ASS/SSA) into cues.
// Styling is removed; sign and song lines are kept as they are.
package subs

import (
	"bytes"
	"errors"
	"fmt"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"unicode/utf8"

	"golang.org/x/text/encoding/japanese"
	"golang.org/x/text/encoding/unicode"
	"golang.org/x/text/transform"
)

// Cue is one subtitle event in milliseconds.
type Cue struct {
	StartMs int64
	EndMs   int64
	Text    string
}

// Format of a subtitle file.
type Format string

const (
	SRT Format = "srt"
	VTT Format = "vtt"
	ASS Format = "ass"
)

// ErrUnknownFormat is returned for files that are not text subtitles.
var ErrUnknownFormat = errors.New("unknown subtitle format")

// FormatFor guesses the format from an mpv codec name or a file name.
func FormatFor(codec, path string) (Format, bool) {
	switch strings.ToLower(codec) {
	case "subrip", "srt":
		return SRT, true
	case "webvtt", "vtt":
		return VTT, true
	case "ass", "ssa":
		return ASS, true
	}
	switch strings.ToLower(filepath.Ext(path)) {
	case ".srt":
		return SRT, true
	case ".vtt":
		return VTT, true
	case ".ass", ".ssa":
		return ASS, true
	}
	return "", false
}

// IsTextCodec reports whether mpv's codec name is a text subtitle that
// ffmpeg can convert to SRT or ASS.
func IsTextCodec(codec string) bool {
	switch strings.ToLower(codec) {
	case "subrip", "srt", "webvtt", "vtt", "ass", "ssa", "mov_text", "text", "microdvd", "subviewer", "jacosub", "sami", "realtext", "mpl2", "pjs", "stl", "vplayer":
		return true
	}
	return false
}

// Decode turns raw file bytes into UTF-8: BOMs are honoured, and text that
// is not valid UTF-8 is read as Shift-JIS (CP932), the usual encoding of
// older Japanese subtitles.
func Decode(data []byte) string {
	switch {
	case bytes.HasPrefix(data, []byte{0xEF, 0xBB, 0xBF}):
		return string(data[3:])
	case bytes.HasPrefix(data, []byte{0xFF, 0xFE}), bytes.HasPrefix(data, []byte{0xFE, 0xFF}):
		out, _, err := transform.Bytes(unicode.UTF16(unicode.LittleEndian, unicode.UseBOM).NewDecoder(), data)
		if err == nil {
			return string(out)
		}
	}
	if utf8.Valid(data) {
		return string(data)
	}
	if out, _, err := transform.Bytes(japanese.ShiftJIS.NewDecoder(), data); err == nil {
		return string(out)
	}
	return strings.ToValidUTF8(string(data), "")
}

// Parse parses decoded subtitle text and returns cues sorted by start time.
func Parse(format Format, text string) ([]Cue, error) {
	text = strings.ReplaceAll(strings.ReplaceAll(text, "\r\n", "\n"), "\r", "\n")
	var cues []Cue
	switch format {
	case SRT, VTT:
		cues = parseBlocks(text)
	case ASS:
		cues = parseASS(text)
	default:
		return nil, fmt.Errorf("%w: %q", ErrUnknownFormat, format)
	}
	return finish(cues), nil
}

// finish drops empty cues and exact duplicates (layered ASS effects) and
// sorts by time.
func finish(in []Cue) []Cue {
	out := make([]Cue, 0, len(in))
	seen := map[Cue]bool{}
	for _, c := range in {
		c.Text = strings.TrimSpace(c.Text)
		if c.Text == "" || c.EndMs < c.StartMs || seen[c] {
			continue
		}
		seen[c] = true
		out = append(out, c)
	}
	sort.SliceStable(out, func(i, j int) bool {
		if out[i].StartMs != out[j].StartMs {
			return out[i].StartMs < out[j].StartMs
		}
		return out[i].EndMs < out[j].EndMs
	})
	return out
}

var (
	timingLine = regexp.MustCompile(`^\s*((?:\d+:)?\d{1,2}:\d{2}[.,]\d{1,3})\s*-->\s*((?:\d+:)?\d{1,2}:\d{2}[.,]\d{1,3})`)
	htmlTag    = regexp.MustCompile(`</?[a-zA-Z][^>]*>`)
	vttTS      = regexp.MustCompile(`<\d+:\d{2}[^>]*>`)
	braceTag   = regexp.MustCompile(`\{[^}]*\}`)
)

// parseBlocks handles SRT and WebVTT: a timing line followed by text lines
// until a blank line.
func parseBlocks(text string) []Cue {
	var cues []Cue
	lines := strings.Split(text, "\n")
	for i := 0; i < len(lines); i++ {
		m := timingLine.FindStringSubmatch(lines[i])
		if m == nil {
			continue
		}
		start, ok1 := parseClock(m[1])
		end, ok2 := parseClock(m[2])
		if !ok1 || !ok2 {
			continue
		}
		var body []string
		for i+1 < len(lines) && strings.TrimSpace(lines[i+1]) != "" {
			i++
			if timingLine.MatchString(lines[i]) {
				i--
				break
			}
			body = append(body, lines[i])
		}
		cues = append(cues, Cue{StartMs: start, EndMs: end, Text: cleanMarkup(strings.Join(body, "\n"))})
	}
	return cues
}

func cleanMarkup(s string) string {
	s = vttTS.ReplaceAllString(s, "")
	s = htmlTag.ReplaceAllString(s, "")
	s = braceTag.ReplaceAllString(s, "")
	r := strings.NewReplacer("&amp;", "&", "&lt;", "<", "&gt;", ">", "&nbsp;", " ", "&lrm;", "", "&rlm;", "")
	s = r.Replace(s)
	lines := strings.Split(s, "\n")
	for i := range lines {
		lines[i] = strings.TrimSpace(lines[i])
	}
	return strings.TrimSpace(strings.Join(lines, "\n"))
}

// parseClock parses "01:02:03,456", "02:03.4" or "1:02:03.45".
func parseClock(s string) (int64, bool) {
	s = strings.Replace(strings.TrimSpace(s), ",", ".", 1)
	frac := int64(0)
	if dot := strings.IndexByte(s, '.'); dot >= 0 {
		f := s[dot+1:]
		if len(f) == 0 || len(f) > 3 {
			return 0, false
		}
		n, err := strconv.Atoi(f)
		if err != nil {
			return 0, false
		}
		for len(f) < 3 {
			f += "0"
			n *= 10
		}
		frac = int64(n)
		s = s[:dot]
	}
	parts := strings.Split(s, ":")
	if len(parts) < 2 || len(parts) > 3 {
		return 0, false
	}
	var total int64
	for _, p := range parts {
		n, err := strconv.Atoi(p)
		if err != nil || n < 0 {
			return 0, false
		}
		total = total*60 + int64(n)
	}
	return total*1000 + frac, true
}

// parseASS reads the [Events] section's Dialogue lines.
func parseASS(text string) []Cue {
	var cues []Cue
	inEvents := false
	format := []string{"layer", "start", "end", "style", "name", "marginl", "marginr", "marginv", "effect", "text"}
	for _, raw := range strings.Split(text, "\n") {
		line := strings.TrimSpace(raw)
		if strings.HasPrefix(line, "[") && strings.HasSuffix(line, "]") {
			inEvents = strings.EqualFold(line, "[Events]")
			continue
		}
		if !inEvents {
			continue
		}
		key, value, ok := strings.Cut(line, ":")
		if !ok {
			continue
		}
		switch strings.ToLower(strings.TrimSpace(key)) {
		case "format":
			format = nil
			for _, f := range strings.Split(value, ",") {
				format = append(format, strings.ToLower(strings.TrimSpace(f)))
			}
		case "dialogue":
			fields := strings.SplitN(strings.TrimLeft(value, " "), ",", len(format))
			if len(fields) != len(format) {
				continue
			}
			var start, end int64
			var body string
			okStart, okEnd := false, false
			for i, name := range format {
				switch name {
				case "start":
					start, okStart = parseClock(fields[i])
				case "end":
					end, okEnd = parseClock(fields[i])
				case "text":
					body = fields[i]
				}
			}
			if okStart && okEnd {
				cues = append(cues, Cue{StartMs: start, EndMs: end, Text: cleanASS(body)})
			}
		}
	}
	return cues
}

var assDrawing = regexp.MustCompile(`\\p[1-9]`)

// cleanASS strips override blocks and turns \N into line breaks. Text drawn
// in vector drawing mode (\p1 … \p0) is not dialogue and is dropped.
func cleanASS(s string) string {
	var b strings.Builder
	drawing := false
	for len(s) > 0 {
		if s[0] == '{' {
			end := strings.IndexByte(s, '}')
			if end < 0 {
				break
			}
			block := s[:end+1]
			if assDrawing.MatchString(block) {
				drawing = true
			} else if strings.Contains(block, `\p0`) {
				drawing = false
			}
			s = s[end+1:]
			continue
		}
		next := strings.IndexByte(s, '{')
		chunk := s
		if next >= 0 {
			chunk = s[:next]
			s = s[next:]
		} else {
			s = ""
		}
		if !drawing {
			b.WriteString(chunk)
		}
	}
	out := strings.NewReplacer(`\N`, "\n", `\n`, "\n", `\h`, " ").Replace(b.String())
	lines := strings.Split(out, "\n")
	for i := range lines {
		lines[i] = strings.TrimSpace(lines[i])
	}
	return strings.TrimSpace(strings.Join(lines, "\n"))
}
