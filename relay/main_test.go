package main

import (
	"bytes"
	"testing"
)

func TestFrameRoundTrip(t *testing.T) {
	payload := []byte{0x45, 0x00, 0x00, 0x14, 1, 2, 3, 4}
	var b bytes.Buffer
	if err := writeFrame(&b, typeIP, payload); err != nil { t.Fatal(err) }
	got, err := readFrame(&b)
	if err != nil { t.Fatal(err) }
	if got.typ != typeIP { t.Fatalf("type=%d", got.typ) }
	if !bytes.Equal(got.payload, payload) { t.Fatalf("payload mismatch") }
}

func TestFrameRejectsBadMagic(t *testing.T) {
	b := bytes.NewBuffer([]byte{0, 0, 0, 0, version, typeIP, 0, 0, 0, 0})
	if _, err := readFrame(b); err == nil { t.Fatal("expected bad magic error") }
}
