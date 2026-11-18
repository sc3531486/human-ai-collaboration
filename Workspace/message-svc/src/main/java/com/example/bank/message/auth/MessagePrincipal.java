package com.example.bank.message.auth;

import java.security.Principal;

public class MessagePrincipal implements Principal {

    private final String subject;
    private final String role;

    public MessagePrincipal(String subject, String role) {
        this.subject = subject;
        this.role = role;
    }

    @Override
    public String getName() {
        return subject;
    }

    public String getSubject() {
        return subject;
    }

    public String getRole() {
        return role;
    }
}
