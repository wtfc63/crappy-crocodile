def process_input(event, context):
    """Triggered by a change to a Cloud Storage bucket.
    Args:
         event (dict): Event payload.
         context (google.cloud.functions.Context): Metadata for the event.
    """
    file = event
    file_hash = preprocess(file)
    publish(file_hash)

def preprocess(file):
    print(f'Preprocessing file: {file["name"]}...')
    print(file)
    return file['md5Hash']

def publish(file_hash):
    print(f'Publishing "file-ready" event for: {file_hash}')
