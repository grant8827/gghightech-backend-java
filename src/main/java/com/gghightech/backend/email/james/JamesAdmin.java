package com.gghightech.backend.email.james;

/** What this service needs from Apache James. Implemented over WebAdmin. */
public interface JamesAdmin {
    void addDomain(String domain);

    /** Also fine if James doesn't have it. */
    void removeDomain(String domain);

    void createUser(String address, String password);

    /** Also fine if James doesn't have it. */
    void deleteUser(String address);
}
