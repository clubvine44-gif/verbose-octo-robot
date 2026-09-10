package main

import (
	"crypto/tls"
	"encoding/binary"
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"sync"
)

const (
	magic       uint32 = 0x4D594B31 // MYK1
	version            = 1
	typeIP       byte  = 1
	typeClose    byte  = 2
	maxPayload        = 65535
	maxPacket         = 65535
)

type frame struct {
	typ     byte
	payload []byte
}

func readFrame(r io.Reader) (frame, error) {
	var h [10]byte
	if _, err := io.ReadFull(r, h[:]); err != nil {
		return frame{}, err
	}
	if binary.BigEndian.Uint32(h[0:4]) != magic {
		return frame{}, fmt.Errorf("invalid MAYAK magic")
	}
	if h[4] != version {
		return frame{}, fmt.Errorf("unsupported MAYAK version: %d", h[4])
	}
	length := int(binary.BigEndian.Uint32(h[6:10]))
	if length > maxPayload {
		return frame{}, fmt.Errorf("invalid frame length: %d", length)
	}
	payload := make([]byte, length)
	if _, err := io.ReadFull(r, payload); err != nil {
		return frame{}, err
	}
	return frame{typ: h[5], payload: payload}, nil
}

func writeFrame(w io.Writer, typ byte, payload []byte) error {
	if len(payload) > maxPayload {
		return fmt.Errorf("payload too large: %d", len(payload))
	}
	var h [10]byte
	binary.BigEndian.PutUint32(h[0:4], magic)
	h[4] = version
	h[5] = typ
	binary.BigEndian.PutUint32(h[6:10], uint32(len(payload)))
	if _, err := w.Write(h[:]); err != nil {
		return err
	}
	_, err := w.Write(payload)
	return err
}

// tunnelClient bridges one authenticated TLS client and the Linux TUN device.
// v0.2 intentionally supports one active client because the Android side uses
// a fixed tunnel address (10.7.0.2). Multi-client addressing will be added with
// explicit per-client tunnel addresses rather than silently sharing one address.
func tunnelClient(conn net.Conn, tun *tunDevice) {
	defer conn.Close()
	var writeMu sync.Mutex
	done := make(chan struct{})
	var once sync.Once
	stop := func() { once.Do(func() { close(done) }) }

	go func() {
		buf := make([]byte, maxPacket)
		for {
			n, err := tun.Read(buf)
			if err != nil {
				stop()
				return
			}
			if n == 0 || (buf[0]>>4) != 4 {
				continue
			}
			packet := append([]byte(nil), buf[:n]...)
			writeMu.Lock()
			err = writeFrame(conn, typeIP, packet)
			writeMu.Unlock()
			if err != nil {
				stop()
				return
			}
		}
	}()

	for {
		select {
		case <-done:
			return
		default:
		}
		f, err := readFrame(conn)
		if err != nil {
			if err != io.EOF && err != io.ErrUnexpectedEOF {
				log.Printf("client %s: %v", conn.RemoteAddr(), err)
			}
			return
		}
		switch f.typ {
		case typeIP:
			if len(f.payload) == 0 || (f.payload[0]>>4) != 4 {
				log.Printf("client %s: rejected non-IPv4 packet", conn.RemoteAddr())
				return
			}
			if _, err := tun.Write(f.payload); err != nil {
				log.Printf("client %s: TUN write: %v", conn.RemoteAddr(), err)
				return
			}
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
	tunName := flag.String("tun", "mayak0", "Linux TUN interface name")
	flag.Parse()

	tun, err := openTUN(*tunName)
	if err != nil {
		log.Fatalf("open TUN: %v", err)
	}
	defer tun.Close()
	log.Printf("MAYAK TUN ready: %s", tun.name)

	cert, err := tls.LoadX509KeyPair(*certFile, *keyFile)
	if err != nil {
		log.Fatalf("load TLS certificate: %v", err)
	}
	cfg := &tls.Config{
		Certificates: []tls.Certificate{cert},
		MinVersion:   tls.VersionTLS12,
		MaxVersion:   tls.VersionTLS13,
	}
	ln, err := tls.Listen("tcp", *listenAddr, cfg)
	if err != nil {
		log.Fatalf("listen: %v", err)
	}
	defer ln.Close()
	log.Printf("MAYAK relay listening on %s (single client)", *listenAddr)

	for {
		conn, err := ln.Accept()
		if err != nil {
			log.Printf("accept: %v", err)
			continue
		}
		log.Printf("client connected: %s", conn.RemoteAddr())
		tunnelClient(conn, tun)
		log.Printf("client disconnected: %s", conn.RemoteAddr())
	}
}
