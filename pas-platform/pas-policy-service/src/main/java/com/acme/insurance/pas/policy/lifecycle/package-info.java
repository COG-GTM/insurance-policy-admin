/**
 * Policy lifecycle writes. AB-525: new business ({@code POST /api/v1/policies}, replaces POLNEW).
 * AB-526 adds renewals and endorsements here, reusing {@link
 * com.acme.insurance.pas.policy.events.OutboxWriter} and the security components.
 */
package com.acme.insurance.pas.policy.lifecycle;
