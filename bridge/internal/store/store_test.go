package store

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func openTest(t *testing.T, path string, mut func(*Options)) *Store {
	t.Helper()
	opts := Options{Path: path, MaxLines: 100, Dedupe: true}
	if mut != nil {
		mut(&opts)
	}
	s, err := Open(opts)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { s.Close() })
	return s
}

func mustAdd(t *testing.T, s *Store, text string) Line {
	t.Helper()
	l, res, err := s.Add(text, "test")
	if err != nil || res != Added {
		t.Fatalf("Add(%q) = %v, %v", text, res, err)
	}
	return l
}

func TestAddTrimsAndDedupesConsecutive(t *testing.T) {
	s := openTest(t, "", nil)
	a := mustAdd(t, s, "  「おはよう」\n")
	if a.ID != 1 || a.Text != "「おはよう」" || a.Source != "test" {
		t.Fatalf("unexpected line %+v", a)
	}
	if _, res, _ := s.Add("「おはよう」", "x"); res != Duplicate {
		t.Fatalf("consecutive duplicate not dropped: %v", res)
	}
	mustAdd(t, s, "次の行")
	if b := mustAdd(t, s, "「おはよう」"); b.ID != 3 {
		t.Fatalf("non-consecutive duplicate should be kept with id 3, got %d", b.ID)
	}
	for _, empty := range []string{"", "   ", "\n\t", "　"} {
		if _, res, _ := s.Add(empty, "x"); res != Filtered {
			t.Fatalf("Add(%q) = %v, want Filtered", empty, res)
		}
	}
}

func TestDedupeDisabled(t *testing.T) {
	s := openTest(t, "", func(o *Options) { o.Dedupe = false })
	mustAdd(t, s, "同じ")
	mustAdd(t, s, "同じ")
	if s.LatestID() != 2 {
		t.Fatal("expected both lines stored")
	}
}

func TestPersistenceAndIDsAcrossRestart(t *testing.T) {
	path := filepath.Join(t.TempDir(), "lines.jsonl")
	s1, err := Open(Options{Path: path, MaxLines: 100, Dedupe: true})
	if err != nil {
		t.Fatal(err)
	}
	mustAdd(t, s1, "一")
	mustAdd(t, s1, "二")
	if err := s1.Close(); err != nil {
		t.Fatal(err)
	}

	s2 := openTest(t, path, nil)
	if got := s2.LatestID(); got != 2 {
		t.Fatalf("LatestID after restart = %d, want 2", got)
	}
	if _, res, _ := s2.Add("二", "x"); res != Duplicate {
		t.Fatal("dedupe state should survive restart")
	}
	if l := mustAdd(t, s2, "三"); l.ID != 3 {
		t.Fatalf("id after restart = %d, want 3", l.ID)
	}
	lines := s2.Last(10)
	if len(lines) != 3 || lines[0].Text != "一" || lines[2].Text != "三" {
		t.Fatalf("unexpected lines %+v", lines)
	}
}

func TestTornFinalLineIsSkipped(t *testing.T) {
	path := filepath.Join(t.TempDir(), "lines.jsonl")
	content := `{"id":1,"text":"ok","source":"a","receivedAt":1}` + "\n" + `{"id":2,"text":"bro`
	if err := os.WriteFile(path, []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}
	s := openTest(t, path, nil)
	if s.LatestID() != 1 {
		t.Fatalf("LatestID = %d, want 1", s.LatestID())
	}
	if l := mustAdd(t, s, "next"); l.ID != 2 {
		t.Fatalf("id = %d, want 2", l.ID)
	}
}

func TestRetentionAndCompaction(t *testing.T) {
	path := filepath.Join(t.TempDir(), "lines.jsonl")
	s := openTest(t, path, func(o *Options) { o.MaxLines = 10 })
	for i := 0; i < 250; i++ {
		mustAdd(t, s, fmt.Sprintf("line %d", i))
	}
	if got := len(s.Last(1000)); got != 10 {
		t.Fatalf("retained %d lines, want 10", got)
	}
	data, _ := os.ReadFile(path)
	if n := strings.Count(string(data), "\n"); n > 10+100 {
		t.Fatalf("file has %d lines, compaction did not run", n)
	}
	s.Close()
	s2 := openTest(t, path, func(o *Options) { o.MaxLines = 10 })
	if s2.LatestID() != 250 {
		t.Fatalf("LatestID after compaction+restart = %d, want 250", s2.LatestID())
	}
	if l := mustAdd(t, s2, "after"); l.ID != 251 {
		t.Fatalf("id = %d, want 251", l.ID)
	}
}

func TestAfterAndLast(t *testing.T) {
	s := openTest(t, "", nil)
	for i := 1; i <= 20; i++ {
		mustAdd(t, s, "l"+string(rune('a'+i)))
	}
	if got := s.After(5, 3); len(got) != 3 || got[0].ID != 6 || got[2].ID != 8 {
		t.Fatalf("After(5,3) = %+v", got)
	}
	if got := s.After(20, 10); len(got) != 0 {
		t.Fatalf("After(20) should be empty, got %d", len(got))
	}
	if got := s.After(0, 100); len(got) != 20 {
		t.Fatalf("After(0) = %d lines", len(got))
	}
	if got := s.Last(3); len(got) != 3 || got[0].ID != 18 || got[2].ID != 20 {
		t.Fatalf("Last(3) = %+v", got)
	}
	if l, ok := s.Get(7); !ok || l.ID != 7 {
		t.Fatal("Get(7) failed")
	}
	if _, ok := s.Get(99); ok {
		t.Fatal("Get(99) should fail")
	}
}

func TestOnAddCalledInOrder(t *testing.T) {
	var got []int64
	s := openTest(t, "", func(o *Options) { o.OnAdd = func(l Line) { got = append(got, l.ID) } })
	mustAdd(t, s, "a")
	s.Add("a", "x") // duplicate: no callback
	mustAdd(t, s, "b")
	if len(got) != 2 || got[0] != 1 || got[1] != 2 {
		t.Fatalf("OnAdd ids = %v", got)
	}
}

func TestFilters(t *testing.T) {
	n, err := NewNormalizer(FilterOptions{
		Drop:      []string{`^\d+$`, `^Textractor`},
		Strip:     []string{`<[^>]+>`, `【[^】]*】`},
		JoinLines: true,
		MaxLength: 12,
	})
	if err != nil {
		t.Fatal(err)
	}
	cases := []struct {
		in   string
		want string
		keep bool
	}{
		{"<b>こんにちは</b>", "こんにちは", true},
		{"【名前】「やあ」", "「やあ」", true},
		{"今日は\r\nいい天気", "今日はいい天気", true},
		{"Hello\nworld", "Hello world", true},
		{"12345", "", false},
		{"Textractor: hooked", "", false},
		{"とても長い文章ですよ本当に長い", "", false},
		{"<i></i>", "", false},
	}
	for _, c := range cases {
		got, keep := n.Normalize(c.in)
		if got != c.want || keep != c.keep {
			t.Errorf("Normalize(%q) = %q,%v want %q,%v", c.in, got, keep, c.want, c.keep)
		}
	}
	if _, err := NewNormalizer(FilterOptions{Drop: []string{"("}}); err == nil {
		t.Fatal("invalid regex should fail")
	}
}

func TestFiltersInStore(t *testing.T) {
	n, _ := NewNormalizer(FilterOptions{Drop: []string{`^skip`}})
	s := openTest(t, "", func(o *Options) { o.Normalizer = n })
	if _, res, _ := s.Add("skip me", "x"); res != Filtered {
		t.Fatalf("res = %v, want Filtered", res)
	}
	if s.LatestID() != 0 {
		t.Fatal("filtered line must not consume an id")
	}
}
