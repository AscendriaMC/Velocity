/*
 * Copyright (C) 2026 Velocity Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.velocitypowered.natives.encryption;

/**
 * A {@link VelocityCipher} whose state lives in native memory and can therefore take part in
 * batched encryption, where many connections are encrypted in one native call.
 *
 * @see VelocityCipherBatch
 */
public interface BatchingVelocityCipher extends VelocityCipher {

  /**
   * Returns the native cipher context backing this cipher.
   *
   * <p>Only meaningful until {@link #close()} is called; passing a stale handle to
   * {@link VelocityCipherBatch#process} is a use-after-free.</p>
   *
   * @return the native context pointer
   */
  long nativeHandle();
}
