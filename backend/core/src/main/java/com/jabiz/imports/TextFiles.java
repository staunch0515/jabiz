package com.jabiz.imports;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackReader;
import java.io.Reader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Files;
import java.nio.file.Path;

/** Opening text files strictly: an undecodable byte refuses the file instead of turning into a replacement mark. */
final class TextFiles {

    private TextFiles() {}

    /** A reader of the file in {@code charset}, without a leading byte order mark. */
    static Reader open(Path file, Charset charset) throws IOException {
        CharsetDecoder decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
        InputStream in = Files.newInputStream(file);
        PushbackReader reader = new PushbackReader(new BufferedReader(new InputStreamReader(in, decoder)), 1);
        int first = read(reader);
        if (first != -1 && first != '﻿') {
            reader.unread(first);
        }
        return reader;
    }

    /** One character; a decoding error becomes an {@link ImportFileException}. */
    static int read(Reader reader) throws IOException {
        try {
            return reader.read();
        } catch (CharacterCodingException e) {
            throw new ImportFileException(null, "The file is not valid text in the expected encoding");
        }
    }

    /** A character a text import never contains: NUL and the other control characters but tab and line breaks. */
    static boolean forbidden(int c) {
        return c < 0x20 && c != '\t' && c != '\n' && c != '\r' && c != '\f' || c == 0x7F;
    }
}
