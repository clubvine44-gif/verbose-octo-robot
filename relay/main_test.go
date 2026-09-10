package main

import (
	"bytes"
	"io"
	"net"
	"testing"
	"time"
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

func TestFrameRejectsOversizedPayload(t *testing.T) {
	b := bytes.NewBuffer([]byte{0x4d, 0x59, 0x4b, 0x31, version, typeIP, 0, 1, 0, 0})
	if _, err := readFrame(b); err == nil { t.Fatal("expected oversized frame error") }
}

func TestFrameRejectsUnsupportedVersion(t *testing.T) {
	b := bytes.NewBuffer([]byte{0x4d, 0x59, 0x4b, 0x31, 2, typeIP, 0, 0, 0, 0})
	if _, err := readFrame(b); err == nil { t.Fatal("expected version error") }
}

type fakeTUN struct {
	readCh  chan []byte
	written chan []byte
}

func newFakeTUN() *fakeTUN {
	return &fakeTUN{readCh: make(chan []byte, 4), written: make(chan []byte, 4)}
}

func (t *fakeTUN) Read(p []byte) (int, error) {
	packet, ok := <-t.readCh
	if !ok { return 0, io.EOF }
	return copy(p, packet), nil
}

func (t *fakeTUN) Write(p []byte) (int, error) {
	packet := append([]byte(nil), p...)
	t.written <- packet
	return len(p), nil
}

func (t *fakeTUN) Close() error {
	select {
	case <-t.readCh:
	default:
	}
	return nil
}

func (t *fakeTUN) stop() { close(t.readCh) }

func TestTunnelClientBridgesBothDirections(t *testing.T) {
	client, server := net.Pipe()
	tun := newFakeTUN()
	done := make(chan struct{})
	go func() {
		tunnelClient(server, tun)
		close(done)
	}()

	outbound := []byte{0x45, 0, 0, 20, 1, 2, 3, 4}
	if err := writeFrame(client, typeIP, outbound); err != nil { t.Fatal(err) }
	select {
	case got := <-tun.written:
		if !bytes.Equal(got, outbound) { t.Fatalf("TUN packet mismatch: %x", got) }
	case <-time.After(time.Second):
		t.Fatal("timed out waiting for TUN write")
	}

	returnPacket := []byte{0x45, 0, 0, 20, 9, 8, 7, 6}
	tun.readCh <- returnPacket
	client.SetReadDeadline(time.Now().Add(time.Second))
	gotFrame, err := readFrame(client)
	if err != nil { t.Fatal(err) }
	if gotFrame.typ != typeIP || !bytes.Equal(gotFrame.payload, returnPacket) {
		t.Fatalf("return frame mismatch: type=%d payload=%x", gotFrame.typ, gotFrame.payload)
	}

	if err := writeFrame(client, typeClose, nil); err != nil { t.Fatal(err) }
	client.Close()
	tun.stop()
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("tunnelClient did not stop")
	}
}

func TestTunnelClientRejectsNonIPv4(t *testing.T) {
	client, server := net.Pipe()
	tun := newFakeTUN()
	done := make(chan struct{})
	go func() {
		tunnelClient(server, tun)
		close(done)
	}()

	if err := writeFrame(client, typeIP, []byte{0x60, 0, 0, 0}); err != nil { t.Fatal(err) }
	client.Close()
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("tunnelClient did not reject non-IPv4")
	}
	tun.stop()
}
