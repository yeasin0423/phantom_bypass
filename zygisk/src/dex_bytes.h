#pragma once
//
// PLACEHOLDER — this file is regenerated at build time.
//
// The GitHub Actions workflow (or a local build, see README) compiles
// hooker/src into a DEX with d8 and converts it with:
//     xxd -i build/dex/classes.dex > zygisk/src/dex_bytes.h
//
// The checked-in placeholder contains a single invalid byte, which makes
// the module boot safely (class loading fails -> no hooks applied) so a
// locally-built module never crashes a device by accident.
//
static const unsigned char classes_dex[] = { 0x00 };
static const unsigned int classes_dex_len = 1;
