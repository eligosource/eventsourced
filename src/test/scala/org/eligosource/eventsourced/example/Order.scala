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
package org.eligosource.eventsourced.example

// --------------------
// domain object
// --------------------

case class Order(id: Int, details: String, validated: Boolean, creditCardNumber: String)

object Order {
  def apply(details: String): Order = apply(details, "")
  def apply(details: String, creditCardNumber: String): Order = new Order(-1, details, false, creditCardNumber)
}

// --------------------
// domain events
// --------------------

case class OrderSubmitted(order: Order)
case class OrderAccepted(order: Order)

case class CredidCardValidationRequested(order: Order)
case class CredidCardValidated(orderId: String)
