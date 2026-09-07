package alx

import (
	"bytes"
	"testing"
	"time"
)

func TestAuthRoundTrip(t *testing.T) {
	token := bytes.Repeat([]byte{0x42}, 32)
	now := time.Unix(1_800_000_000, 0)
	var wire bytes.Buffer
	if err := WriteAuth(&wire, token, now); err != nil {
		t.Fatal(err)
	}
	auth, err := ReadAuth(&wire)
	if err != nil {
		t.Fatal(err)
	}
	if !auth.Verify(token, now.Add(10*time.Second), time.Minute) {
		t.Fatal("fresh auth was rejected")
	}
	if auth.Verify(token, now.Add(2*time.Minute), time.Minute) {
		t.Fatal("expired auth was accepted")
	}
}

func TestDatagramRoundTrip(t *testing.T) {
	want := Datagram{AssociationID: 7, Address: "1.1.1.1:53", Payload: []byte("dns")}
	raw, err := EncodeDatagram(want)
	if err != nil {
		t.Fatal(err)
	}
	got, err := DecodeDatagram(raw)
	if err != nil {
		t.Fatal(err)
	}
	if got.AssociationID != want.AssociationID || got.Address != want.Address || !bytes.Equal(got.Payload, want.Payload) {
		t.Fatalf("round trip mismatch: %#v", got)
	}
}

func TestUDPFrameRoundTrip(t *testing.T) {
	var wire bytes.Buffer
	if err := WriteUDPFrame(&wire, "[2606:4700:4700::1111]:53", []byte{1, 2, 3}); err != nil {
		t.Fatal(err)
	}
	address, payload, err := ReadUDPFrame(&wire)
	if err != nil {
		t.Fatal(err)
	}
	if address != "[2606:4700:4700::1111]:53" || !bytes.Equal(payload, []byte{1, 2, 3}) {
		t.Fatalf("unexpected frame: %q %v", address, payload)
	}
}

func TestPingOpenRoundTrip(t *testing.T) {
	var wire bytes.Buffer
	if err := WriteOpen(&wire, OpenRequest{Command: CommandPing}); err != nil {
		t.Fatal(err)
	}
	request, err := ReadOpen(&wire)
	if err != nil {
		t.Fatal(err)
	}
	if request.Command != CommandPing || request.Address != "" || request.AssociationID != 0 {
		t.Fatalf("unexpected ping request: %#v", request)
	}
}
