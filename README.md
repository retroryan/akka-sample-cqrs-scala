This tutorial contains a sample illustrating an CQRS design with [Akka Cluster Sharding](https://doc.akka.io/docs/akka/2.6/typed/cluster-sharding.html), [Akka Cluster Singleton](https://doc.akka.io/docs/akka/2.6/typed/cluster-singleton.html), [Akka Persistence](https://doc.akka.io/docs/akka/2.6/typed/persistence.html) and [Akka Persistence Query](https://doc.akka.io/docs/akka/2.6/persistence-query.html).

## Overview

This sample application implements a CQRS-ES design that will side-effect in the read model on selected events persisted to Cassandra by the write model. In this sample, the side-effect is logging a line. A more practical example would be to send a message to a Kafka topic or update a relational database.

## Write model

The write model is a shopping cart.

The implementation is based on a sharded actor: each `ShoppingCart` is an [Akka Cluster Sharding](https://doc.akka.io/docs/akka/2.6/typed/cluster-sharding.html) entity. The entity actor `ShoppingCart` is an [EventSourcedBehavior](https://doc.akka.io/docs/akka/2.6/typed/persistence.html).

Events from the shopping carts are tagged and consumed by the read model.

## Read model

The read model is implemented in such a way that 'load' is sharded over a number of processors. This number is `event-processor.parallelism`.

The implementation is resilient: it uses an *Akka Cluster Singleton* in combination with *Akka Cluster Sharding* to always keep the event processors alive.

## Running the sample code

1. Start a Cassandra server by running:

```
sbt "runMain sample.cqrs.Main cassandra"
```

2. Start a node that runs the write model:

```
export CASSANDRA_CONTACT_POINT_ONE="127.0.0.1"
export CLUSTER_ROLE_ONE="write-model"
export CLUSTER_ROLE_TWO="read-model"
export AKKA_CLUSTER_PORT=2551
export HTTP_PORT=8051
sbt "runMain sample.cqrs.Main"
```

3. Start a node that runs the read model:

```
export CONF_ENV="dev"
export CASSANDRA_CONTACT_POINT_ONE="127.0.0.1"
export CLUSTER_ROLE_ONE="read-model"
export AKKA_CLUSTER_PORT=2552
export HTTP_PORT=8052
sbt "runMain sample.cqrs.Main"
```

4. More write or read nodes can be started started by defining roles and port:

```
export CONF_ENV="dev"
export CLUSTER_ROLE_ONE="write-model"
sbt -Dakka.cluster.roles.0=write-model "runMain sample.cqrs.Main 2553"
export CLUSTER_ROLE_ONE="read-model"
sbt -Dakka.cluster.roles.0=read-model "runMain sample.cqrs.Main 2554"
```

Try it with curl:

```
export SERVER_HOST=35.223.174.159
# add item to cart
curl -X POST -H "Content-Type: application/json" -d '{"cartId":"cart1", "itemId":"socks", "quantity":3}' http://35.232.208.63:8080/shopping/carts
curl -X POST -H "Content-Type: application/json" -d '{"cartId":"cart1", "itemId":"socks", "quantity":3}' http://localhost:8080/shopping/carts

# get cart
curl http://127.0.0.1:8051/shopping/carts/cart1

# update quantity of item
curl -X PUT -H "Content-Type: application/json" -d '{"cartId":"cart1", "itemId":"socks", "quantity":5}' http://127.0.0.1:8051/shopping/carts

# check out cart
curl -X POST -H "Content-Type: application/json" -d '{}' http://127.0.0.1:8051/shopping/carts/cart1/checkout
```

or same `curl` commands to port 8052.

Docker Image
=================

sbt docker:publishLocal

Akka Cluster
============

curl -sL https://github.com/operator-framework/operator-lifecycle-manager/releases/download/0.13.0/install.sh | bash -s 0.13.0


kubectl create -f https://operatorhub.io/install/akka-cluster-operator.yaml

kubectl get csv -n operators


Cassandra
============
https://github.com/instaclustr/cassandra-operator/blob/master/doc/op_guide.md

kubectl apply -f deploy/crds.yaml
kubectl apply -f deploy/bundle.yaml
kubectl get pods | grep cassandra-operator
kubectl apply -f examples/example-datacenter-minimal.yaml
kubectl get pods | grep cassandra-test
kubectl exec cassandra-test-dc-cassandra-rack1-0 -c cassandra -- nodetool status



Kubernetes Commands:
=========================
kubectl create -f kubernetes/namespace.json
kubectl config set-context --current --namespace=cqrs
