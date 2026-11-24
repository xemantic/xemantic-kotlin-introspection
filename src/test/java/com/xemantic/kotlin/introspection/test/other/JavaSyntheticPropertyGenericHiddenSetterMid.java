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

/**
 * A generic Java class of a getter of its type variable, and of setters of it which are not part of the API,
 * which kotlinc substitutes in its subclasses, unlike a private one, taking them before the public one of its superclass.
 */
public class JavaSyntheticPropertyGenericHiddenSetterMid<T> extends JavaSyntheticPropertyGenericHiddenSetterTop {

    public T getP() {
        return null;
    }

    protected void setP(T p) {}

    public T getQ() {
        return null;
    }

    void setQ(T q) {}

    public T getR() {
        return null;
    }

    private void setR(Integer r) {}

    public Integer getS() {
        return 0;
    }

    void setS(T s) {}

}
