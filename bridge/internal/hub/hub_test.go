package hub

import "testing"

func TestPublishAndSlowSubscriberDropped(t *testing.T) {
	h := New()
	fast := h.Subscribe(10)
	slow := h.Subscribe(1)
	h.Publish(1)
	h.Publish(2) // slow's buffer is full: it gets dropped
	if v := <-fast.C; v != 1 {
		t.Fatal(v)
	}
	if v := <-fast.C; v != 2 {
		t.Fatal(v)
	}
	if v := <-slow.C; v != 1 {
		t.Fatal(v)
	}
	if _, ok := <-slow.C; ok {
		t.Fatal("slow subscriber should be closed")
	}
	slow.Close() // idempotent
	fast.Close()
	fast.Close()
	h.Publish(3) // no subscribers, must not panic
}
