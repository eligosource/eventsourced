/*
 * Copyright 2012 Eligotech BV.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.eligosource.eventsourced.core

/**
 * Journal actor configuration object.
 */
trait JournalProps {
  /**
   * Optional channel name.
   */
  def name: Option[String]

  /**
   * Optional dispatcher name.
   */
  def dispatcherName: Option[String]

  /**
   * Creates a [[org.eligosource.eventsourced.core.Journal]] actor instance.
   */
  def journal: Journal
}
