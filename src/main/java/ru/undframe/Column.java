package ru.undframe;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.RUNTIME)
public @interface Column {

    String head();
    boolean main() default false;
    boolean link() default false;
    boolean constantPosition() default false;
}
