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

import com.xemantic.kotlin.introspection.test.KotlinSyntheticPropertyPrimitiveSetterW;
import org.jspecify.annotations.NonNull;

/**
 * A Java class of a getter, and a setter of its type, taking a boxed type annotated not null,
 * next to the setter of a Kotlin interface taking the primitive type, a JVM method of its own, which kotlinc keeps apart.
 */
public abstract class JavaSyntheticPropertyBoxedSetterTwin implements KotlinSyntheticPropertyPrimitiveSetterW {

    public Integer getW() {
        return 0;
    }

    public void setW(@NonNull Integer w) {}

}
