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
 * A Java class of getters, which Kotlin also reads as synthetic properties, like `name` of `getName()`,
 * a `var` one with a setter of the same type, like `setName(String)`, and of methods which are no such getters.
 */
public class JavaSyntheticPropertyBase {

    public int code;

    public String getName() { return ""; }

    public void setName(String name) {}

    public boolean isActive() { return true; }

    public String getURL() { return ""; }

    public int getCount() { return 0; }

    // not of the type of getCount(), so that count stays a val
    public void setCount(String count) {}

    public String getPr() { return ""; }

    // less visible than getPr(), so that pr can be assigned only in a subclass, where it can be read anywhere
    protected void setPr(String pr) {}

    public String getAt(int index) { return ""; }

    public <T> T getAny() { return null; }

    public void getNothing() {}

    public String getaB() { return ""; }

    public String getLabel() { return ""; }

    // the field code wins over the synthetic property
    public String getCode() { return ""; }

}
