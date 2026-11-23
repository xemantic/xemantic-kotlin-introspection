/*
 * xemantic-kotlin-introspection - Kotlin API introspection of the classpath, rendering LLM-friendly signatures
 * Copyright (C) 2026  Kazimierz Pogoda / Xemantic
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.xemantic.kotlin.introspection.test.other;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * A Java class of getters next to setters which Kotlin takes, or does not take, for the setter of the synthetic property,
 * as verified with kotlinc 2.4.21: only one of a single parameter, which is no vararg, whatever it returns,
 * of the type of the getter, a platform type being the same as either nullability.
 */
public class JavaSyntheticPropertySetters {

    public String getName() { return ""; }

    public void setName(String name) {}

    // an overload, which is no setter
    public void setName(String... names) {}

    public String getVa() { return ""; }

    // no setter, so that va stays a val
    public void setVa(String... va) {}

    public String getBar() { return ""; }

    // a setter returning a value is one too
    public JavaSyntheticPropertySetters setBar(String bar) { return this; }

    public @Nullable String getNick() { return null; }

    // stricter than the getter, so that nick stays a val
    public void setNick(@NonNull String nick) {}

    public @NonNull String getLax() { return ""; }

    // laxer than the getter, so that lax stays a val too
    public void setLax(@Nullable String lax) {}

    public @NonNull String getStrict() { return ""; }

    // a platform type is the same as the type not nullable
    public void setStrict(String strict) {}

    public int getBoxed() { return 0; }

    // the platform type Int! is the same as Int
    public void setBoxed(Integer boxed) {}

    public int getNullableBoxed() { return 0; }

    // but Int? is not
    public void setNullableBoxed(@Nullable Integer nullableBoxed) {}

    public List<@Nullable String> getItems() { return null; }

    // of another type argument than the getter
    public void setItems(List<@NonNull String> items) {}

    public List<? extends String> getWild() { return null; }

    // a Java collection is both the mutable one, and the read-only one, covariant, so that the types are the same
    public void setWild(List<String> wild) {}

    public Map<? extends String, String> getKeys() { return null; }

    // unlike a Map, which is not covariant in its keys
    public void setKeys(Map<String, String> keys) {}

    public Object getWide() { return ""; }

    public void setWide(Object wide) {}

    public List<?> getStars() { return null; }

    // a List<*> is the List<Any!> of a platform type
    public void setStars(List<Object> stars) {}

    public Integer getFirst() { return 0; }

    // the first setter of the type of the getter, in the order of declaration, is the one taken
    public void setFirst(Integer first) {}

    protected void setFirst(int first) {}

    public Integer getHidden() { return 0; }

    // also one which cannot be called, so that the public one declared after it is not taken
    private void setHidden(Integer hidden) {}

    public void setHidden(int hidden) {}

    public Object getNarrow() { return ""; }

    // not of the type of getNarrow(), but of the one of an override returning String
    public void setNarrow(String narrow) {}

}
