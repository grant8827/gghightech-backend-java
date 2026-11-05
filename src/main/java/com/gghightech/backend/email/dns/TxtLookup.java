package com.gghightech.backend.email.dns;

import java.util.List;

/** TXT records for a name, each with its character-strings joined. Empty if none. */
public interface TxtLookup {
    List<String> txt(String name);
}
