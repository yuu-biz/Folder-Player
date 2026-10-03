/*
 * Fixture generator for DSD test files (self-written, public domain for this project).
 *
 *   dsdgen dsf  out.dsf  <seconds> <freqL> <freqR>   2.8224 MHz 1-bit stereo DSF (LSB first, 4096-byte blocks)
 *   dsdgen dff  out.dff  <seconds> <freqL> <freqR>   DSDIFF 1.5, uncompressed DSD (MSB first, byte interleaved)
 *   dsdgen dst  out.dff                              DSDIFF declaring DST compression (must be rejected as unsupported)
 *
 * Audio is a sine per channel (amplitude 0.5) through a 2nd-order sigma-delta modulator, so a correct DSD→PCM
 * decoder yields a recognizable tone (frequency checked by the instrumentation test).
 */
#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define RATE 2822400

static void le32(FILE *f, uint32_t v) { for (int i = 0; i < 4; i++) fputc((v >> (8 * i)) & 0xff, f); }
static void le64(FILE *f, uint64_t v) { for (int i = 0; i < 8; i++) fputc((int)((v >> (8 * i)) & 0xff), f); }
static void be16(FILE *f, uint16_t v) { fputc(v >> 8, f); fputc(v & 0xff, f); }
static void be32(FILE *f, uint32_t v) { for (int i = 3; i >= 0; i--) fputc((v >> (8 * i)) & 0xff, f); }
static void be64(FILE *f, uint64_t v) { for (int i = 7; i >= 0; i--) fputc((int)((v >> (8 * i)) & 0xff), f); }

typedef struct { double i1, i2; } sdm_t;

static int sdm_step(sdm_t *s, double x) {
    double y = (s->i2 >= 0) ? 1.0 : -1.0;
    s->i1 += x - y;
    s->i2 += s->i1 - y;
    return y > 0;
}

/* Returns one bit-packed byte (8 samples) for a channel; msb_first selects bit order. */
static uint8_t next_byte(sdm_t *s, double freq, uint64_t *n, int msb_first) {
    uint8_t b = 0;
    for (int k = 0; k < 8; k++) {
        double x = 0.5 * sin(2.0 * M_PI * freq * (double)(*n) / RATE);
        int bit = sdm_step(s, x);
        (*n)++;
        if (msb_first) b |= (uint8_t)(bit << (7 - k));
        else b |= (uint8_t)(bit << k);
    }
    return b;
}

static int write_dsf(const char *path, double seconds, double fl, double fr) {
    FILE *f = fopen(path, "wb");
    if (!f) return 1;
    const uint32_t block = 4096;
    uint64_t samples = (uint64_t)(seconds * RATE);
    uint64_t bytes_per_ch = (samples + 7) / 8;
    uint64_t blocks = (bytes_per_ch + block - 1) / block;
    uint64_t data_bytes = blocks * block * 2;
    uint64_t total = 28 + 52 + 12 + data_bytes;
    fwrite("DSD ", 1, 4, f); le64(f, 28); le64(f, total); le64(f, 0);
    fwrite("fmt ", 1, 4, f); le64(f, 52);
    le32(f, 1); le32(f, 0); le32(f, 2); le32(f, 2); le32(f, RATE); le32(f, 1);
    le64(f, samples); le32(f, block); le32(f, 0);
    fwrite("data", 1, 4, f); le64(f, 12 + data_bytes);
    sdm_t sl = {0}, sr = {0};
    uint64_t nl = 0, nr = 0;
    uint8_t *bl = malloc(block), *br = malloc(block);
    for (uint64_t b = 0; b < blocks; b++) {
        for (uint32_t i = 0; i < block; i++) {
            uint64_t idx = b * block + i;
            if (idx < bytes_per_ch) {
                bl[i] = next_byte(&sl, fl, &nl, 0);
                br[i] = next_byte(&sr, fr, &nr, 0);
            } else {
                bl[i] = 0x69; br[i] = 0x69; /* DSD idle pattern padding */
            }
        }
        fwrite(bl, 1, block, f);
        fwrite(br, 1, block, f);
    }
    free(bl); free(br);
    fclose(f);
    return 0;
}

static void dff_prop(FILE *f, int dst) {
    /* PROP chunk: SND + FS + CHNL + CMPR */
    const char *cmpr_name = dst ? "DST Encoded" : "not compressed";
    uint8_t name_len = (uint8_t)strlen(cmpr_name);
    uint32_t cmpr_size = 4 + 1 + name_len;
    if (cmpr_size & 1) cmpr_size++;
    uint32_t prop_size = 4 + (12 + 4) + (12 + 2 + 8) + (12 + cmpr_size);
    fwrite("PROP", 1, 4, f); be64(f, prop_size); fwrite("SND ", 1, 4, f);
    fwrite("FS  ", 1, 4, f); be64(f, 4); be32(f, RATE);
    fwrite("CHNL", 1, 4, f); be64(f, 2 + 8); be16(f, 2); fwrite("SLFT", 1, 4, f); fwrite("SRGT", 1, 4, f);
    fwrite("CMPR", 1, 4, f); be64(f, cmpr_size); fwrite(dst ? "DST " : "DSD ", 1, 4, f);
    fputc(name_len, f); fwrite(cmpr_name, 1, name_len, f);
    if ((4 + 1 + name_len) & 1) fputc(0, f);
}

static int write_dff(const char *path, double seconds, double fl, double fr) {
    FILE *f = fopen(path, "wb");
    if (!f) return 1;
    uint64_t samples = (uint64_t)(seconds * RATE);
    uint64_t bytes_per_ch = samples / 8;
    uint64_t data = bytes_per_ch * 2;
    const char *cmpr_name = "not compressed";
    uint32_t cmpr_size = 4 + 1 + (uint32_t)strlen(cmpr_name);
    if (cmpr_size & 1) cmpr_size++;
    uint64_t prop_size = 4 + 16 + 22 + 12 + cmpr_size;
    uint64_t frm = 4 + (12 + 4) + (12 + prop_size) + (12 + data);
    fwrite("FRM8", 1, 4, f); be64(f, frm); fwrite("DSD ", 1, 4, f);
    fwrite("FVER", 1, 4, f); be64(f, 4); be32(f, 0x01050000);
    dff_prop(f, 0);
    fwrite("DSD ", 1, 4, f); be64(f, data);
    sdm_t sl = {0}, sr = {0};
    uint64_t nl = 0, nr = 0;
    for (uint64_t i = 0; i < bytes_per_ch; i++) {
        fputc(next_byte(&sl, fl, &nl, 1), f);
        fputc(next_byte(&sr, fr, &nr, 1), f);
    }
    fclose(f);
    return 0;
}

static int write_dst(const char *path) {
    FILE *f = fopen(path, "wb");
    if (!f) return 1;
    const char *cmpr_name = "DST Encoded";
    uint32_t cmpr_size = 4 + 1 + (uint32_t)strlen(cmpr_name);
    if (cmpr_size & 1) cmpr_size++;
    uint64_t prop_size = 4 + 16 + 22 + 12 + cmpr_size;
    /* DST chunk: FRTE (frame count + rate) and one DSTF frame with dummy payload. */
    const uint32_t frame_len = 64;
    uint64_t dst_size = 4 + (12 + 6) + (12 + frame_len);
    uint64_t frm = 4 + (12 + 4) + (12 + prop_size) + (12 + dst_size);
    fwrite("FRM8", 1, 4, f); be64(f, frm); fwrite("DSD ", 1, 4, f);
    fwrite("FVER", 1, 4, f); be64(f, 4); be32(f, 0x01050000);
    dff_prop(f, 1);
    fwrite("DST ", 1, 4, f); be64(f, dst_size);
    fwrite("FRTE", 1, 4, f); be64(f, 6); be32(f, 1); be16(f, 75);
    fwrite("DSTF", 1, 4, f); be64(f, frame_len);
    for (uint32_t i = 0; i < frame_len; i++) fputc(i & 0xff, f);
    fclose(f);
    return 0;
}

int main(int argc, char **argv) {
    if (argc >= 3 && strcmp(argv[1], "dst") == 0) return write_dst(argv[2]);
    if (argc < 6) {
        fprintf(stderr, "usage: dsdgen dsf|dff out seconds freqL freqR | dsdgen dst out\n");
        return 2;
    }
    double sec = atof(argv[3]), fl = atof(argv[4]), fr = atof(argv[5]);
    if (strcmp(argv[1], "dsf") == 0) return write_dsf(argv[2], sec, fl, fr);
    if (strcmp(argv[1], "dff") == 0) return write_dff(argv[2], sec, fl, fr);
    return 2;
}
