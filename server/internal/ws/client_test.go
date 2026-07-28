package ws

import "testing"

func TestClientMessageRateLimit(t *testing.T) {
	c := newClient(nil, nil, 0, 0, nil)

	for i := 0; i < int(msgSendBurst); i++ {
		if got := c.checkFrameRate(OpMsgSend); got != frameRateAllowed {
			t.Fatalf("message burst frame %d: got %v, want allowed", i+1, got)
		}
	}
	if got := c.checkFrameRate(OpMsgSend); got != frameRateDrop {
		t.Fatalf("first excess message frame: got %v, want drop", got)
	}
	if got := c.checkFrameRate(OpMsgSend); got != frameRateDrop {
		t.Fatalf("second excess message frame: got %v, want drop", got)
	}
	if got := c.checkFrameRate(OpMsgSend); got != frameRateClose {
		t.Fatalf("third excess message frame: got %v, want close", got)
	}
}

func TestClientServiceRateLimitIsPerOpcode(t *testing.T) {
	c := newClient(nil, nil, 0, 0, nil)

	for i := 0; i < int(serviceFrameBurst); i++ {
		if got := c.checkFrameRate(OpPing); got != frameRateAllowed {
			t.Fatalf("ping burst frame %d: got %v, want allowed", i+1, got)
		}
	}
	if got := c.checkFrameRate(OpPing); got != frameRateDrop {
		t.Fatalf("first excess ping frame: got %v, want drop", got)
	}
	if got := c.checkFrameRate(OpPong); got != frameRateAllowed {
		t.Fatalf("pong should have an independent counter: got %v, want allowed", got)
	}
}
