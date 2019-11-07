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

# Read configuration and check if required env variables are present
CONFIG=./config.sh 
if [ -f $CONFIG ]; then
	source $CONFIG
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

if [[ -z "${PREFIX}" ]]; then
	echo "Please configure the prefix for the GCP object names by setting the PREFIX environment variable"
	exit -1
fi

# Configuration
bucket_input="$PREFIX-input"
bucket_input_retention=3600s
gs_input="gs://$bucket_input/"
bucket_proc="$PREFIX-processing"
bucket_proc_retention=1d
gs_proc="gs://$bucket_proc/"
bucket_output="$PREFIX-output"
bucket_output_retention=10d
gs_output="gs://$bucket_output/"

# Set GCP project and default location
echo "Setting GCP project to '$GCP_PROJECT_ID' (PROJECT_NUMBER=$project_nr) and default location to '$GCP_LOCATION'..."
gcloud config set project $GCP_PROJECT_ID
gcloud config set composer/location $GCP_LOCATION

# Create Cloud Storage Buckets (and optionally remove them if they already exist)
mapfile -t buckets < <(gsutil ls)
if [[ " ${buckets[@]} " =~ $gs_input ]]; then
	confirm "The input Bucket already exists. Delete it and all its objects? [y/N]" && \
		gsutil rm -r $gs_input && \
		gsutil mb -s standard -l $GCP_LOCATION -b on --retention $bucket_input_retention $gs_input && \
		gsutil iam ch allUsers:objectViewer $gs_input
else
	gsutil mb -s standard -l $GCP_LOCATION -b on --retention $bucket_input_retention $gs_input
	gsutil iam ch allUsers:objectViewer $gs_input
fi
if [[ " ${buckets[@]} " =~ $gs_proc ]]; then
	confirm "The processing Bucket already exists. Delete it and all its objects? [y/N]" && \
		gsutil rm -r $gs_proc && \
		gsutil mb -s standard -l $GCP_LOCATION -b on --retention $bucket_proc_retention $gs_proc
else
	gsutil mb -s standard -l $GCP_LOCATION -b on --retention $bucket_proc_retention $gs_proc
fi
if [[ " ${buckets[@]} " =~ $gs_output ]]; then
	confirm "The output Bucket already exists. Delete it and all its objects? [y/N]" && \
		gsutil rm -r $gs_output && \
		gsutil mb -s standard -l $GCP_LOCATION -b on --retention $bucket_output_retention $gs_output && \
		gsutil iam ch allUsers:objectViewer $gs_output
else
	gsutil mb -s standard -l $GCP_LOCATION -b on --retention $bucket_output_retention $gs_output
	gsutil iam ch allUsers:objectViewer $gs_output
fi

# Deploy Cloud Functions
gcloud functions deploy "$PREFIX-process-input" \
	--region $GCP_LOCATION \
	--runtime python37 \
	--source "./src/process-input/" \
	--entry-point "process_input" \
	--trigger-resource $bucket_input \
	--trigger-event google.storage.object.finalize \
	--memory 128MB \
	--allow-unauthenticated

