#!/bin/bash

usage() {
  echo "Usage: $0 -a api -c config_file -p project -u"
  echo "project is mandatory. The rest are optionals."
  echo "u option is for unlocking the package after the build has finished"
}

api=https://api.opensuse.org
config_file=$HOME/.oscrc
lock="yes"

while getopts ":a:c:p:u" o;do
    case "${o}" in
        a)
            api=${OPTARG}
            ;;
        c)
            config_file=${OPTARG}
            ;;
        p)
            project=${OPTARG}
            ;;
        u)
            lock="no"
            ;;    
        :)
            echo "ERROR: Option -$OPTARG requires an argument"
            ;;
        \?)
            echo "ERROR: Invalid option -$OPTARG"
            usage
            ;;
    esac
done
shift $((OPTIND-1))

echo "DEBUG: api: $api ; config_file: $config_file ; project $project; lock: $lock"

if [ -z "${project}" ];then
    usage
    exit -1
fi 

for i in $(osc -A $api -c $config_file ls $project);do
    echo "Checking $project/$i"
    osc -A $api -c $config_file results $project $i -w
    if [ $lock == "yes" ];then
      echo "Locking $project/$i so there are not further rebuilds"
      osc -A $api -c $config_file lock $project $i
    fi  
done
