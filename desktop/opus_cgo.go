package main

/*
#cgo pkg-config: opus

#include <opus/opus.h>
#include <stdlib.h>

static OpusEncoder* create_opus_encoder(int sample_rate, int channels, int *err) {
	OpusEncoder *enc = opus_encoder_create(sample_rate, channels, OPUS_APPLICATION_VOIP, err);
	if (enc && *err == OPUS_OK) {
		opus_encoder_ctl(enc, OPUS_SET_SIGNAL(OPUS_SIGNAL_VOICE));
		opus_encoder_ctl(enc, OPUS_SET_DTX(1));
		opus_encoder_ctl(enc, OPUS_SET_INBAND_FEC(1));
		opus_encoder_ctl(enc, OPUS_SET_COMPLEXITY(8));
		opus_encoder_ctl(enc, OPUS_SET_BITRATE(32000));
	}
	return enc;
}

static int encode_opus_frame(OpusEncoder *enc, const short *pcm, int frame_size, unsigned char *out, int max_bytes) {
	return opus_encode(enc, pcm, frame_size, out, max_bytes);
}

static void destroy_opus_encoder(OpusEncoder *enc) {
	if (enc) {
		opus_encoder_destroy(enc);
	}
}

static OpusDecoder* create_opus_decoder(int sample_rate, int channels, int *err) {
	return opus_decoder_create(sample_rate, channels, err);
}

static int decode_opus_frame(OpusDecoder *dec, const unsigned char *in, int in_len, short *out_pcm, int frame_size) {
	return opus_decode(dec, in, in_len, out_pcm, frame_size, 0);
}

static void destroy_opus_decoder(OpusDecoder *dec) {
	if (dec) {
		opus_decoder_destroy(dec);
	}
}
*/
import "C"
import (
	"fmt"
	"unsafe"
)

type NativeOpusEncoder struct {
	enc *C.OpusEncoder
}

func NewNativeOpusEncoder(sampleRate, channels int) (*NativeOpusEncoder, error) {
	var errCode C.int
	enc := C.create_opus_encoder(C.int(sampleRate), C.int(channels), &errCode)
	if errCode != C.OPUS_OK || enc == nil {
		return nil, fmt.Errorf("failed to create opus encoder: error code %d", int(errCode))
	}
	return &NativeOpusEncoder{enc: enc}, nil
}

func (e *NativeOpusEncoder) Encode(pcm []int16, out []byte) (int, error) {
	if len(pcm) == 0 || len(out) == 0 {
		return 0, nil
	}
	n := C.encode_opus_frame(
		e.enc,
		(*C.short)(unsafe.Pointer(&pcm[0])),
		C.int(len(pcm)),
		(*C.uchar)(unsafe.Pointer(&out[0])),
		C.int(len(out)),
	)
	if n < 0 {
		return 0, fmt.Errorf("opus encode error: %d", int(n))
	}
	return int(n), nil
}

func (e *NativeOpusEncoder) Close() {
	if e.enc != nil {
		C.destroy_opus_encoder(e.enc)
		e.enc = nil
	}
}

type NativeOpusDecoder struct {
	dec *C.OpusDecoder
}

func NewNativeOpusDecoder(sampleRate, channels int) (*NativeOpusDecoder, error) {
	var errCode C.int
	dec := C.create_opus_decoder(C.int(sampleRate), C.int(channels), &errCode)
	if errCode != C.OPUS_OK || dec == nil {
		return nil, fmt.Errorf("failed to create opus decoder: error code %d", int(errCode))
	}
	return &NativeOpusDecoder{dec: dec}, nil
}

func (d *NativeOpusDecoder) Decode(in []byte, pcmOut []int16) (int, error) {
	if len(pcmOut) == 0 {
		return 0, nil
	}
	var inPtr *C.uchar
	if len(in) > 0 {
		inPtr = (*C.uchar)(unsafe.Pointer(&in[0]))
	}
	n := C.decode_opus_frame(
		d.dec,
		inPtr,
		C.int(len(in)),
		(*C.short)(unsafe.Pointer(&pcmOut[0])),
		C.int(len(pcmOut)),
	)
	if n < 0 {
		return 0, fmt.Errorf("opus decode error: %d", int(n))
	}
	return int(n), nil
}

func (d *NativeOpusDecoder) Close() {
	if d.dec != nil {
		C.destroy_opus_decoder(d.dec)
		d.dec = nil
	}
}
