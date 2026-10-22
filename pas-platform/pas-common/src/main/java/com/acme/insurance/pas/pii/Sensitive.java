package com.acme.insurance.pas.pii;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target({
  ElementType.RECORD_COMPONENT,
  ElementType.FIELD,
  ElementType.METHOD,
  ElementType.PARAMETER
})
public @interface Sensitive {
  Category value();

  enum Category {
    REGULATED,
    PERSONAL
  }
}
