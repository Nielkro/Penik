package main

/*
#cgo pkg-config: rnnoise
#cgo LDFLAGS: -lrnnoise

#include <rnnoise.h>
#include <stdlib.h>

typedef struct {
    DenoiseState *state;
    float in_buf[480];
    float out_buf[480];
} NativeRNNoise;

static NativeRNNoise* create_rnnoise() {
    DenoiseState *st = rnnoise_create(NULL);
    if (!st) return NULL;
    NativeRNNoise *rn = (NativeRNNoise*)calloc(1, sizeof(NativeRNNoise));
    rn->state = st;
    return rn;
}

static void destroy_rnnoise(NativeRNNoise *rn) {
    if (rn) {
        if (rn->state) {
            rnnoise_destroy(rn->state);
        }
        free(rn);
    }
}

// Processes 480 samples (10ms at 48kHz), returns VAD speech probability (0.0 to 1.0)
static float process_rnnoise_chunk(NativeRNNoise *rn, const short *pcm_in, short *pcm_out) {
    if (!rn || !rn->state || !pcm_in || !pcm_out) return 0.0f;

    for (int i = 0; i < 480; i++) {
        rn->in_buf[i] = (float)pcm_in[i];
    }

    float vad = rnnoise_process_frame(rn->state, rn->out_buf, rn->in_buf);

    for (int i = 0; i < 480; i++) {
        float val = rn->out_buf[i];
        if (val > 32767.0f) val = 32767.0f;
        else if (val < -32768.0f) val = -32768.0f;
        pcm_out[i] = (short)val;
    }

    return vad;
}
*/
import "C"
import (
	"fmt"
	"unsafe"
)

type NativeRNNoise struct {
	rn *C.NativeRNNoise
}

func NewNativeRNNoise() (*NativeRNNoise, error) {
	rn := C.create_rnnoise()
	if rn == nil {
		return nil, fmt.Errorf("failed to create RNNoise denoiser")
	}
	return &NativeRNNoise{rn: rn}, nil
}

// ProcessFrameDenoise processes 960 samples (20ms at 48kHz) in two 10ms neural network passes.
// Modifies pcm in-place and returns the max VAD speech probability (0.0 to 1.0).
func (r *NativeRNNoise) ProcessFrameDenoise(pcm []int16) float32 {
	if r.rn == nil || len(pcm) < 960 {
		return 1.0
	}

	vad1 := float32(C.process_rnnoise_chunk(
		r.rn,
		(*C.short)(unsafe.Pointer(&pcm[0])),
		(*C.short)(unsafe.Pointer(&pcm[0])),
	))

	vad2 := float32(C.process_rnnoise_chunk(
		r.rn,
		(*C.short)(unsafe.Pointer(&pcm[480])),
		(*C.short)(unsafe.Pointer(&pcm[480])),
	))

	if vad2 > vad1 {
		return vad2
	}
	return vad1
}

func (r *NativeRNNoise) Close() {
	if r.rn != nil {
		C.destroy_rnnoise(r.rn)
		r.rn = nil
	}
}
