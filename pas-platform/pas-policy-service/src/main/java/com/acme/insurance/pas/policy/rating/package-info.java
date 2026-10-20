/**
 * Online rating flows (quotes, rating on new business / renewal / endorsement). The rating engine
 * itself is the shared {@code com.acme.insurance.pas.rating} component in {@code pas-rating}
 * (AB-522); inject {@code PremiumRatingService} rather than duplicating rates here.
 */
package com.acme.insurance.pas.policy.rating;
