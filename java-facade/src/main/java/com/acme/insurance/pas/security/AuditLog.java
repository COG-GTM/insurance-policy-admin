package com.acme.insurance.pas.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Records who called the policy API, what they asked for and the outcome. */
public final class AuditLog {

    private static final Logger LOG = LoggerFactory.getLogger("com.acme.insurance.pas.audit");

    private AuditLog() {
    }

    public static void record(String client, String method, String resource,
                              int status, String remoteAddr) {
        LOG.info("client={} method={} resource={} status={} remote={}",
                clean(client), clean(method), clean(resource), status, clean(remoteAddr));
    }

    static String clean(String value) {
        if (value == null) {
            return "-";
        }
        String s = value.length() > 128 ? value.substring(0, 128) : value;
        return s.replaceAll("[^A-Za-z0-9._:/*-]", "_");
    }
}
