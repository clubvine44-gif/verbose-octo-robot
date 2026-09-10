package main

import (
	"crypto/tls"
	"encoding/binary"
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"os"
)

const (
	magic       uint32 = 0x4D594B31 // MYK1
	version            = 1
	typeIP       byte  = 1
	typeClose    byte  = 2
	maxPayload        = 65535
)

type frame struct {
	typ     byte
	payload []byte
}

func readFrame(r io.Reader) (frame, error) {
	var h [10]byte
	if _, err := io.ReadFull(r, h[:]); err != nil { return frame{}, err }
	if binary.BigEndian.Uint32(h[0:4]) != magic { return frame{}, fmt.Errorf("invalid MAYAK magic") }
	if h[4] != version { return frame{}, fmt.Errorf("unsupported MAYAK version: %d", h[4]) }
	length := int64(binary.BigEndian.Uint32(h[6:10]))
	if length < 0 || length > maxPayload { return frame{}, fmt.Errorf("invalid frame length: %d", length) }
	p := make([]byte, length)
	if _, err := io.ReadFull(r, p); err != nil { return frame{}, err }
	return frame{typ: h[5], payload: p}, nil
}

func writeFrame(w io.Writer, typ byte, payload []byte) error {
	if len(payload) > maxPayload { return fmt.Errorf("payload too large: %d", len(payload)) }
	var h [10]byte
	binary.BigEndian.PutUint32(h[0:4], magic)
	h[4] = version
	h[5] = typ
	binary.BigEndian.PutUint32(h[6:10], uint32(len(payload)))
	if _, err := w.Write(h[:]); err != nil { return err }
	_, err := w.Write(payload)
	return err
}

func handleClient(conn net.Conn, target string) {
	defer conn.Close()
	for {
		f, err := readFrame(conn)
		if err != nil {
			if err != io.EOF && err != io.ErrUnexpectedEOF { log.Printf("client %s: %v", conn.RemoteAddr(), err) }
			return
		}
		switch f.typ {
		case typeIP:
			// v0.2 terminates and validates the transport frame. Forwarding raw IP
			// requires a privileged server-side TUN interface, added in the next step.
			log.Printf("received IPv4 packet from %s: %d bytes (target=%s)", conn.RemoteAddr(), len(f.payload), target)
		case typeClose:
			return
		default:
			log.Printf("client %s: unsupported frame type %d", conn.RemoteAddr(), f.typ)
			return
		}
	}
}

func main() {
	listenAddr := flag.String("listen", ":4433", "TLS listen address")
	certFile := flag.String("cert", "server.crt", "TLS certificate PEM")
	keyFile := flag.String("key", "server.key", "TLS private key PEM")
	target := flag.String("target", "internet", "forwarding target label")
	flag.Parse()

	cert, err := tls.LoadX509KeyPair(*certFile, *keyFile)
	if err != nil { log.Fatalf("load TLS certificate: %v", err) }
	cfg := &tls.Config{Certificates: []tls.Certificate{cert}, MinVersion: tls.VersionTLS12}
	ln, err := tls.Listen("tcp", *listenAddr, cfg)
	if err != nil { log.Fatalf("listen: %v", err) }
	defer ln.Close()
	log.Printf("MAYAK relay listening on %s", *listenAddr)

	for {
		conn, err := ln.Accept()
		if err != nil {
			if os.IsTemporary(err) { log.Printf("temporary accept error: %v", err); continue }
			log.Printf("accept: %v", err)
			continue
		}
		go handleClient(conn, *target)
	}
}
