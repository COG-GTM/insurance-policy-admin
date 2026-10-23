/**
 * Pure POLRNW/POLEND rules shared by pas-policy-service and the parity adapters (AB-526). No I/O:
 * callers load the policy, persist the result and own the transaction.
 */
package com.acme.insurance.pas.lifecycle;
