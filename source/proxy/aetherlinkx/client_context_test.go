package aetherlinkx

import (
	"context"
	"testing"

	"github.com/xtls/xray-core/common/session"
)

func TestDetachedPayloadContextSurvivesTimeoutOnlyParentCancellation(t *testing.T) {
	parent, cancelParent := context.WithCancel(
		session.ContextWithTimeoutOnly(context.Background(), true),
	)
	payload, cancelPayload := detachedPayloadContext(parent)
	if payload == nil || cancelPayload == nil {
		t.Fatal("timeout-only context did not create a detached payload context")
	}

	cancelParent()
	select {
	case <-payload.Done():
		t.Fatal("parent cancellation leaked into the detached payload context")
	default:
	}

	cancelPayload()
	select {
	case <-payload.Done():
	default:
		t.Fatal("detached payload context was not cancellable by its owner")
	}
}

func TestRegularContextIsNotDetached(t *testing.T) {
	payload, cancel := detachedPayloadContext(context.Background())
	if payload != nil || cancel != nil {
		t.Fatal("regular context was unexpectedly detached")
	}
}
