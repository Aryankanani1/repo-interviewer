#!/bin/sh
# Logs autoscaling every 5 s while a load test runs (task 3.4): time, then per scaled service (the workers'
# Deployments until task 4.1) the consumer lag per pod that the HPA sees (KEDA's metric; one value per trigger), the
# replicas the HPA wants, and the pods that are Ready.
# Usage: loadtest/sample-scaling.sh > scaling.log   (stop with Ctrl-C)
NS=repo-interviewer
echo "time      interview-service(lag desired ready)   analysis-service(lag desired ready)"
while true; do
  line=$(date +%H:%M:%S)
  for w in interview-service analysis-service; do
    lag=$(kubectl -n $NS get hpa keda-hpa-$w \
      -o jsonpath='{range .status.currentMetrics[*]}{.external.current.averageValue}{","}{end}' 2>/dev/null)
    desired=$(kubectl -n $NS get hpa keda-hpa-$w -o jsonpath='{.status.desiredReplicas}' 2>/dev/null)
    ready=$(kubectl -n $NS get deployment $w -o jsonpath='{.status.readyReplicas}' 2>/dev/null)
    line="$line   $w lag=${lag%,} desired=$desired ready=${ready:-0}"
  done
  echo "$line"
  sleep 5
done
