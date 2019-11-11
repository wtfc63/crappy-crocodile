#!/bin/bash

# Function to simplify "[y/N]" CLI prompts
confirm() {
    # call with a prompt string or it will use a default
    read -r -p "${1:-Are you sure? [y/N]} " response
    case "$response" in
        [yY][eE][sS]|[yY]) 
            true
            ;;
        *)
            false
            ;;
    esac
}

# Check if required tools (the Google Cloud SDK) are installed
PREFIX="# PREREQUISITES CHECK #"
if [[ -z `command -v gcloud` ]]; then
    echo "$PREFIX Please install the Google Cloud SDK and add it to the path!"
    exit -1
fi
if [[ -z `command -v gsutil` ]]; then
    echo "$PREFIX Please install 'gsutil' (included in the Google Cloud SDK) and add it to the path!"
    exit -1
fi
if [[ -z `command -v docker-credential-gcr` ]]; then
    echo "$PREFIX Please install 'docker-credential-gcr' (included in the Google Cloud SDK) and add it to the path!"
    exit -1
fi

# Read configuration and check if required env variables are present
CONFIG=./config.sh 
if [[ -f ${CONFIG} ]]; then
	source ${CONFIG}
fi

if [[ -z "${GCP_PROJECT_ID}" ]]; then
	echo "Please configure the GCP project by setting the GCP_PROJECT_ID environment variable"
	exit -1
fi
project_nr=$( gcloud projects list --filter="$GCP_PROJECT_ID" --format="value(PROJECT_NUMBER)" )

if [[ -z "${GCP_LOCATION}" ]]; then
	echo "Please configure the GCP location by setting the GCP_LOCATION environment variable"
	exit -1
fi
if [[ -z "${GCR_HOST}" ]]; then
	echo "Please configure the Google Cloud Container Registry host by setting the GCR_HOST environment variable"
	exit -1
fi

if [[ -z "${PREFIX}" ]]; then
	echo "Please configure the prefix for the GCP object names by setting the PREFIX environment variable"
	exit -1
fi

# Configuration
service_account="$PREFIX-srv"
credentials_file="gcp-credentials.json"
bucket_input="$PREFIX-input"
gs_input="gs://$bucket_input/"
bucket_proc="$PREFIX-processing"
gs_proc="gs://$bucket_proc/"
bucket_output="$PREFIX-output"
bucket_output_retention=10d
gs_output="gs://$bucket_output/"
topic_new_video="$PREFIX-file-ready"
topic_started="$PREFIX-processing-started"
topic_completed="$PREFIX-processing-completed"
function_process_input="$PREFIX-process-input"
service_init_analysis="$PREFIX-init-analysis"
service_init_analysis_image="$GCR_HOST/$GCP_PROJECT_ID/$service_init_analysis"
service_init_analysis_subscription="$service_init_analysis-subscription"

# Set GCP project and default location
echo "# Setting GCP project to '$GCP_PROJECT_ID' (PROJECT_NUMBER=$project_nr) and default location to '$GCP_LOCATION'..."
gcloud config set project ${GCP_PROJECT_ID}
gcloud config set composer/location ${GCP_LOCATION}
gcloud config set run/region ${GCP_LOCATION}
gcloud auth configure-docker
gcloud services enable videointelligence.googleapis.com

if [[ $* != *--teardown ]]; then

    # Setup the GCP Service Accounts
    if [[ $* != *--skip-account-init* ]]; then
        service_account_email=$(
            gcloud iam service-accounts list --filter="display_name=$service_account" --format="value(email)"
        )
        if [[ ! ${service_account_email} =~ $service_account ]]; then
            gcloud iam service-accounts create ${service_account} --display-name ${service_account}
            gcloud projects add-iam-policy-binding ${GCP_PROJECT_ID} \
                --member "serviceAccount:$service_account@$GCP_PROJECT_ID.iam.gserviceaccount.com" \
                --role "roles/owner"
        fi
    fi

    # Create Cloud Storage Buckets (and optionally remove them if they already exist)
    if [[ $* != *--skip-bucket-init* ]]; then
        mapfile -t buckets < <(gsutil ls)
        if [[ " ${buckets[@]} " =~ $gs_input ]]; then
            confirm "The input Bucket already exists. Delete it and all its objects? [y/N]" && \
                gsutil rm -r ${gs_input} && \
                gsutil mb -s standard -l ${GCP_LOCATION} -b on ${gs_input} && \
                gsutil iam ch allUsers:objectViewer ${gs_input}
        else
            gsutil mb -s standard -l ${GCP_LOCATION} -b on ${gs_input}
            gsutil iam ch allUsers:objectViewer ${gs_input}
        fi
        if [[ " ${buckets[@]} " =~ $gs_proc ]]; then
            confirm "The processing Bucket already exists. Delete it and all its objects? [y/N]" && \
                gsutil rm -r ${gs_proc} && \
                gsutil mb -s standard -l ${GCP_LOCATION} -b on ${gs_proc}
        else
            gsutil mb -s standard -l ${GCP_LOCATION} -b on ${gs_proc}
        fi
        if [[ " ${buckets[@]} " =~ $gs_output ]]; then
            confirm "The output Bucket already exists. Delete it and all its objects? [y/N]" && \
                gsutil rm -r ${gs_output} && \
                gsutil mb -s standard -l ${GCP_LOCATION} -b on --retention ${bucket_output_retention} ${gs_output} && \
                gsutil iam ch allUsers:objectViewer $gs_output
        else
            gsutil mb -s standard -l ${GCP_LOCATION} -b on --retention ${bucket_output_retention} ${gs_output}
            gsutil iam ch allUsers:objectViewer ${gs_output}
        fi
    fi

    # Setup Cloud Pub/Sub Topics
    if [[ $* != *--skip-topic-init* ]]; then
        mapfile -t topics < <(gcloud pubsub topics list | grep -v "\-\-\-" | cut -d' ' -f 2)
        if [[ " ${topics[@]} " =~ $topic_new_video ]]; then
            gcloud pubsub topics delete ${topic_new_video}
        fi
        if [[ " ${topics[@]} " =~ $topic_started ]]; then
            gcloud pubsub topics delete ${topic_started}
        fi
        if [[ " ${topics[@]} " =~ $topic_completed ]]; then
            gcloud pubsub topics delete ${topic_completed}
        fi
        gcloud pubsub topics create ${topic_new_video}
        gcloud pubsub topics create ${topic_started}
        gcloud pubsub topics create ${topic_completed}
    fi

    # Deploy Cloud Functions
    if [[ $* != *--skip-function-init* ]]; then
        echo -e "\n# Deployment of Cloud Function '$function_process_input'..."
        gcloud functions deploy ${function_process_input} \
            --region ${GCP_LOCATION} \
            --runtime python37 \
            --source "./src/process-input/" \
            --entry-point "process_input" \
            --update-env-vars "PREFIX=$PREFIX" \
            --trigger-resource ${bucket_input} \
            --trigger-event google.storage.object.finalize \
            --memory 128MB \
            --allow-unauthenticated
    fi

    # Build & Deploy Cloud Run Services
    if [[ $* != *--skip-cloudrun-init* ]]; then
        if [[ ! -f ${credentials_file} ]]; then
            gcloud iam service-accounts keys create ${credentials_file} \
                --iam-account "$service_account@$GCP_PROJECT_ID.iam.gserviceaccount.com"
        fi

        echo -e "\n# Deployment of Cloud Run Service '$service_init_analysis'..."
        cd src/init-analysis
        mkdir -p "src/main/jib" && cp "../../$credentials_file" "src/main/jib/$credentials_file"
        cat  > gradle.properties <<EOF
gcpProjectId=${GCP_PROJECT_ID}
gcrImage=${service_init_analysis}
EOF
        ./gradlew jib
        gcloud beta run deploy \
            ${service_init_analysis} \
            --image ${service_init_analysis_image} \
            --memory 512Mi \
            --timeout 10m \
            --update-env-vars "GOOGLE_APPLICATION_CREDENTIALS=$credentials_file" \
            --platform managed \
            --allow-unauthenticated
        service_init_analysis_url=$(
            gcloud beta run services describe ${service_init_analysis} \
                --platform managed \
                --format="value(status.address.url)"
        )
        subscription=$(
            gcloud beta pubsub subscriptions list \
                --filter="topic=projects/$GCP_PROJECT_ID/topics/$topic_new_video" \
                --format="value(name)"
        )
        if [[ ! ${subscription} =~ $service_init_analysis_subscription ]]; then
            gcloud beta pubsub subscriptions create ${service_init_analysis_subscription} \
                --topic ${topic_new_video} \
                --push-endpoint=${service_init_analysis_url}
        fi

        cd ../..
    fi
else
    echo -e "\nTearing down the system..."

    echo "# Removing the Pub/Sub Subscriptions if present..."
    subscription=$(
        gcloud beta pubsub subscriptions list \
            --filter="$service_init_analysis_subscription" \
            --format="value(name)"
    )
    if [[ ${subscription} =~ $service_init_analysis_subscription ]]; then
        gcloud beta pubsub subscriptions delete ${service_init_analysis_subscription}
    fi

    echo "# Removing the Cloud Run Services if present..."
    service=$(
        gcloud beta run services list \
            --platform managed \
            --filter ${service_init_analysis} \
            --format "value(SERVICE)"
    )
    if [[ ${service} =~ $service_init_analysis ]]; then
        gcloud beta run services delete ${service_init_analysis} --platform managed
    fi

    echo "# Removing the Cloud Functions if present..."
    function=$(
        gcloud functions list --filter ${function_process_input} --format "value(NAME)"
    )
    if [[ ${function} =~ $function_process_input ]]; then
        gcloud functions delete ${function_process_input} --region ${GCP_LOCATION}
    fi

    echo "# Removing the Pub/Sub Topics if present..."
    mapfile -t topics < <(gcloud pubsub topics list | grep -v "\-\-\-" | cut -d' ' -f 2)
    if [[ " ${topics[@]} " =~ $topic_new_video ]]; then
        gcloud pubsub topics delete ${topic_new_video}
    fi
    if [[ " ${topics[@]} " =~ $topic_started ]]; then
        gcloud pubsub topics delete ${topic_started}
    fi
    if [[ " ${topics[@]} " =~ $topic_completed ]]; then
        gcloud pubsub topics delete ${topic_completed}
    fi

    echo "# Removing the Storage Buckets if present..."
    mapfile -t buckets < <(gsutil ls)
    if [[ " ${buckets[@]} " =~ $gs_input ]]; then
        gsutil rm -r ${gs_input}
    fi
    if [[ " ${buckets[@]} " =~ $gs_proc ]]; then
        gsutil rm -r ${gs_proc}
    fi
    if [[ " ${buckets[@]} " =~ $gs_output ]]; then
        gsutil rm -r ${gs_output}
    fi

fi