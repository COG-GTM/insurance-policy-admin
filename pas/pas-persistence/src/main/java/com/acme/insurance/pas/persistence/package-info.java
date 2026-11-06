/**
 * Repositories and Flyway migrations for the PAS schema (AB-521, ADR 0001 D3). May depend on
 * pas-domain only.
 *
 * <p>Migrations live in {@code db/migration/<vendor>} ({@code postgresql}, {@code db2}); each epic
 * ticket uses only its allocated version range (see pas/pas-persistence/README.md).
 */
package com.acme.insurance.pas.persistence;
