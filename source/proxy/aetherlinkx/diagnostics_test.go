package aetherlinkx

import (
	"errors"
	"strings"
	"testing"
)

func TestClientDiagnosticsNeverExposeMultilineData(t *testing.T) {
	before := ClientDiagnostics()
	markClientStage("process")
	markClientStage("dial")
	markClientError("server-accept", errors.New("first line\nsecond line;third line"))
	after := ClientDiagnostics()
	if before == after {
		t.Fatal("diagnostic counters did not change")
	}
	if strings.ContainsAny(after, "\r\n;") {
		t.Fatalf("diagnostic snapshot is not single-line safe: %q", after)
	}
	for _, expected := range []string{"connections=", "dial_ok=", "failures=", "last_stage=server-accept", "second line,third line"} {
		if !strings.Contains(after, expected) {
			t.Fatalf("missing %q in %q", expected, after)
		}
	}
}
