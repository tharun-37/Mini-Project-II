import os
import sqlite3
from datetime import datetime
from flask import Flask, request, jsonify, render_template, send_from_directory
from flask_cors import CORS
from werkzeug.utils import secure_filename

app = Flask(__name__)
CORS(app)

# Configuration
UPLOAD_FOLDER = 'uploads'
DATABASE = 'reports.db'
ALLOWED_EXTENSIONS = {'png', 'jpg', 'jpeg'}

app.config['UPLOAD_FOLDER'] = UPLOAD_FOLDER
os.makedirs(UPLOAD_FOLDER, exist_ok=True)

# WhatsApp Meta API Credentials
WHATSAPP_TOKEN = 'EAAUP2niDMhUBRmfjm7rsD350w8KFLiM0efpKq7Ls0TSqoFnAGoB3scLWD2pvY32eYdy5kV1RqtECZA31g5BShawI55xMZAKdGs9LZADxZCZBVXERebtwzptC4qaWIzYIYK72CGOPXMJYZC0F7K8bj17N0SLTonNZAfa3VAFGXphNVqgShrR1Snu3PXYMLHXQUibybHw74vTtbEMebIEIkWXldRROaZBJkZAEBZBNTM4nKahDoBCnS23s3Id3xgiPzqL4BvGntxYBpWFFoKYGG4I9D76gu4'
WHATSAPP_PHONE_ID = '1171402059380527'
TEST_RECIPIENT_NUMBER = '916381901189' # Verified Test Number

def get_db_connection():
    conn = sqlite3.connect(DATABASE)
    conn.row_factory = sqlite3.Row
    return conn

def init_db():
    conn = get_db_connection()
    conn.execute('''
        CREATE TABLE IF NOT EXISTS violations (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            plate_number TEXT NOT NULL,
            violation_type TEXT NOT NULL,
            timestamp TEXT NOT NULL,
            image_filename TEXT NOT NULL,
            status TEXT DEFAULT 'Pending'
        )
    ''')
    conn.commit()
    conn.close()

init_db()

def allowed_file(filename):
    return '.' in filename and filename.rsplit('.', 1)[1].lower() in ALLOWED_EXTENSIONS

@app.route('/')
def dashboard():
    return render_template('index.html')

@app.route('/uploads/<name>')
def download_file(name):
    return send_from_directory(app.config["UPLOAD_FOLDER"], name)

@app.route('/api/reports', methods=['GET'])
def get_reports():
    conn = get_db_connection()
    reports = conn.execute('SELECT * FROM violations ORDER BY id DESC').fetchall()
    conn.close()
    return jsonify([dict(ix) for ix in reports])

@app.route('/api/report', methods=['POST'])
def create_report():
    uploaded_files = []
    
    # Check for multiple images (image1, image2, image3)
    for i in range(1, 4):
        field_name = f'image{i}'
        if field_name in request.files:
            file = request.files[field_name]
            if file and file.filename != '' and allowed_file(file.filename):
                filename = secure_filename(f"{datetime.now().strftime('%Y%m%d%H%M%S')}_seq{i}_{file.filename}")
                file.save(os.path.join(app.config['UPLOAD_FOLDER'], filename))
                uploaded_files.append(filename)
                
    # Fallback to single 'image' field if no seq images found
    if not uploaded_files and 'image' in request.files:
        file = request.files['image']
        if file and file.filename != '' and allowed_file(file.filename):
            filename = secure_filename(f"{datetime.now().strftime('%Y%m%d%H%M%S')}_{file.filename}")
            file.save(os.path.join(app.config['UPLOAD_FOLDER'], filename))
            uploaded_files.append(filename)
            
    if not uploaded_files:
        return jsonify({'error': 'No valid images uploaded'}), 400
        
    plate_number = request.form.get('plate_number', 'UNKNOWN')
    violation_type = request.form.get('violation_type', 'UNKNOWN_VIOLATION')
    
    # Store all filenames as a comma-separated string
    image_filenames_str = ",".join(uploaded_files)
    timestamp = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    
    conn = get_db_connection()
    conn.execute('INSERT INTO violations (plate_number, violation_type, timestamp, image_filename) VALUES (?, ?, ?, ?)',
                 (plate_number, violation_type, timestamp, image_filenames_str))
    conn.commit()
    conn.close()
    
    return jsonify({'success': True, 'message': f'{len(uploaded_files)} image(s) successfully saved.'}), 201

@app.route('/api/reports/<int:id>/status', methods=['POST'])
def update_status(id):
    data = request.get_json()
    new_status = data.get('status')
    if not new_status:
        return jsonify({'error': 'Missing status'}), 400
        
    conn = get_db_connection()
    conn.execute('UPDATE violations SET status = ? WHERE id = ?', (new_status, id))
    conn.commit()
    conn.close()
    
    return jsonify({'success': True})

@app.route('/api/reports/<int:id>/whatsapp', methods=['POST'])
def send_whatsapp(id):
    import requests
    
    conn = get_db_connection()
    report = conn.execute('SELECT * FROM violations WHERE id = ?', (id,)).fetchone()
    conn.close()
    
    if not report:
        return jsonify({'error': 'Report not found'}), 404
        
    image_filenames = report['image_filename'].split(',')
    first_image_path = os.path.join(app.config['UPLOAD_FOLDER'], image_filenames[0])
    
    if not os.path.exists(first_image_path):
        return jsonify({'error': 'Evidence image not found on server disk'}), 404
        
    # Step 1: Upload the local evidence image to Meta's WhatsApp Media API to obtain a media_id
    media_url = f"https://graph.facebook.com/v17.0/{WHATSAPP_PHONE_ID}/media"
    media_headers = {
        "Authorization": f"Bearer {WHATSAPP_TOKEN}"
    }
    
    try:
        with open(first_image_path, 'rb') as f:
            files = {
                'file': (image_filenames[0], f, 'image/jpeg')
            }
            data = {
                'messaging_product': 'whatsapp',
                'type': 'image/jpeg'
            }
            media_response = requests.post(media_url, headers=media_headers, data=data, files=files)
            
        if media_response.status_code != 200:
            return jsonify({
                'error': 'Failed to upload evidence to Meta Media API',
                'meta_error': media_response.json()
            }), 400
            
        media_id = media_response.json().get('id')
    except Exception as e:
        return jsonify({'error': f'Media upload connection error: {str(e)}'}), 500
        
    # Step 2: Send the uploaded image containing custom details as a caption to the recipient
    message_url = f"https://graph.facebook.com/v17.0/{WHATSAPP_PHONE_ID}/messages"
    message_headers = {
        "Authorization": f"Bearer {WHATSAPP_TOKEN}",
        "Content-Type": "application/json"
    }
    
    payload = {
        "messaging_product": "whatsapp",
        "to": TEST_RECIPIENT_NUMBER,
        "type": "image",
        "image": {
            "id": media_id,
            "caption": (
                f"*TRAFFIC EYE VIOLATION REPORT*\n\n"
                f"*Plate Number:* {report['plate_number']}\n"
                f"*Violations:* {report['violation_type']}\n"
                f"*Timestamp:* {report['timestamp']}\n\n"
                f"Please visit the nearby Police Station to pay the fine."
            )
        }
    }
    
    try:
        response = requests.post(message_url, json=payload, headers=message_headers)
        data = response.json()
        
        if response.status_code == 200:
            return jsonify({'success': True, 'message': 'WhatsApp violation report sent!', 'meta_response': data})
        else:
            # Handle standard Meta customer service window limits
            meta_err_msg = data.get('error', {}).get('message', '')
            if '24 hours' in meta_err_msg or 'window' in meta_err_msg:
                user_msg = (
                    "To send custom violation images, please send a quick message (e.g. 'hi') "
                    "from your phone to your Meta Business test number to open the 24-hour developer window!"
                )
                return jsonify({'error': user_msg, 'meta_error': data}), 400
                
            return jsonify({'error': 'Failed to send WhatsApp message', 'meta_error': data}), 400
    except Exception as e:
        return jsonify({'error': str(e)}), 500

if __name__ == '__main__':
    app.run(debug=True, host='0.0.0.0', port=5000)
