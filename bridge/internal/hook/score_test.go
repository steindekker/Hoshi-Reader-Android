package hook

import (
	"testing"
	"time"
)

func TestScorePrefersDialogue(t *testing.T) {
	now := time.Date(2026, 10, 4, 12, 0, 0, 0, time.UTC)
	dialogue := []Sample{
		{Text: "「おはよう。今日はいい天気だね。」", At: now.UnixMilli()},
		{Text: "「そうだね、散歩に行こう。」", At: now.Add(time.Second).UnixMilli()},
	}
	garbage := []Sample{
		{Text: `C:\game\data\script.ks`, At: now.UnixMilli()},
		{Text: "texture.png", At: now.Add(time.Millisecond).UnixMilli()},
	}
	spam := []Sample{
		{Text: "ああああああああ", At: now.UnixMilli()},
	}
	d := scoreThread(dialogue, []time.Time{now, now.Add(time.Second)}, now.Add(2*time.Second))
	g := scoreThread(garbage, []time.Time{now, now.Add(time.Millisecond)}, now.Add(2*time.Second))
	s := scoreThread(spam, []time.Time{now}, now)
	if d < 0.5 {
		t.Errorf("dialogue score %v, want >= 0.5", d)
	}
	if g > d/2 {
		t.Errorf("path score %v should be well below dialogue %v", g, d)
	}
	if s > 0.2 {
		t.Errorf("repeated-character score %v, want <= 0.2", s)
	}
}
