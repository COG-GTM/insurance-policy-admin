package com.acme.insurance.pas.policy.newbusiness;

import com.acme.insurance.pas.policy.inquiry.PolicyResponse;

public record NewBusinessResponse(boolean shadow, PolicyResponse policy) {}
